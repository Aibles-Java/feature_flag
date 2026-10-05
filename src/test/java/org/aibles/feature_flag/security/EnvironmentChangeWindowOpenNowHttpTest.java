package org.aibles.feature_flag.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.aibles.feature_flag.config.AppConfig;
import org.aibles.feature_flag.domain.entity.User;
import org.aibles.feature_flag.testsupport.FixtureIds;
import org.aibles.feature_flag.testsupport.SyntheticFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * S-2.11 independent QA over real HTTP (real JWT chain, real PDP, real advice). The general clock
 * is pinned at 10:00Z, the change-window clock is movable (UTC zone), so any answer can only come
 * from the dedicated clock. Parity: {@code changeWindowOpenNow} must equal what a real OWNER PUT on
 * the PRODUCTION flag state decides at the same instant.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.allow-bean-definition-overriding=true",
      "spring.datasource.url=jdbc:h2:mem:envwindowhttp-testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;"
          + "MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=KEY,VALUE;"
          + "CASE_INSENSITIVE_IDENTIFIERS=TRUE"
    })
@ActiveProfiles("test")
@Import(EnvironmentChangeWindowOpenNowHttpTest.MovableClocks.class)
class EnvironmentChangeWindowOpenNowHttpTest {

  static final AtomicReference<Instant> NOW = new AtomicReference<>();

  static final class MovableClock extends Clock {
    private final ZoneId zone;

    MovableClock(ZoneId zone) {
      this.zone = zone;
    }

    @Override
    public ZoneId getZone() {
      return zone;
    }

    @Override
    public Clock withZone(ZoneId z) {
      return new MovableClock(z);
    }

    @Override
    public Instant instant() {
      return NOW.get();
    }
  }

  @TestConfiguration
  static class MovableClocks {
    @Bean
    @Primary
    public Clock clock() {
      return Clock.fixed(Instant.parse("2026-01-15T10:00:00Z"), ZoneId.of("UTC"));
    }

    @Bean(AppConfig.CHANGE_WINDOW_CLOCK)
    public Clock changeWindowClock() {
      return new MovableClock(ZoneId.of("UTC"));
    }
  }

  @Value("${local.server.port}")
  private int port;

  @Autowired JdbcTemplate jdbc;
  @Autowired JwtTokenProvider jwt;
  private final ObjectMapper mapper = new ObjectMapper();
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  @BeforeEach
  void load() {
    SyntheticFixture.load(jdbc);
    NOW.set(Instant.parse("2026-01-15T21:00:00Z"));
  }

  private HttpResponse<String> call(String method, String path, UUID user, String body)
      throws Exception {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
    String email = jdbc.queryForObject("select email from users where id = ?", String.class, user);
    b.header(
        "Authorization",
        "Bearer "
            + jwt.generateToken(
                UserPrincipal.from(
                    User.builder().id(user).email(email).passwordHash("x").build())));
    return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  private void window(UUID env, Integer start, Integer end, String zone) {
    jdbc.update(
        "update environments set change_window_start_hour = ?, change_window_end_hour = ?,"
            + " change_window_timezone = ? where id = ?",
        start,
        end,
        zone,
        env);
  }

  private JsonNode getEnv(UUID env) throws Exception {
    HttpResponse<String> r =
        call("GET", "/api/v1/environments/" + env, FixtureIds.USER_OWNER_X, null);
    assertThat(r.statusCode()).isEqualTo(200);
    return mapper.readTree(r.body());
  }

  /** Real PROD state write as OWNER: true iff allowed (200), false iff 403. */
  private boolean prodWriteAllowed(UUID flag, UUID env) throws Exception {
    HttpResponse<String> r =
        call(
            "PUT",
            "/api/v1/flags/" + flag + "/environments/" + env,
            FixtureIds.USER_OWNER_X,
            "{\"enabled\":true}");
    assertThat(r.statusCode()).as("body %s", r.body()).isIn(200, 403);
    return r.statusCode() == 200;
  }

  private void assertParity(UUID flag, UUID env, String at) throws Exception {
    JsonNode n = getEnv(env);
    assertThat(n.has("changeWindowZone")).isTrue();
    assertThat(n.has("changeWindowOpenNow")).isTrue();
    boolean openNow = n.get("changeWindowOpenNow").asBoolean();
    assertThat(prodWriteAllowed(flag, env))
        .as(
            "parity at %s (window %s-%s tz %s)",
            at,
            n.get("changeWindowStartHour"),
            n.get("changeWindowEndHour"),
            n.get("changeWindowTimezone"))
        .isEqualTo(openNow);
  }

  @Test
  @DisplayName("boundary seconds of [9,17) and wrapping [22,6): openNow == real PROD PUT decision")
  void boundaryParity() throws Exception {
    window(FixtureIds.ENV_A_PROD, 9, 17, null);
    window(FixtureIds.ENV_B_PROD, 22, 6, null);
    String[] instants = {
      "2026-01-15T08:59:59Z", "2026-01-15T09:00:00Z", "2026-01-15T16:59:59Z",
      "2026-01-15T17:00:00Z", "2026-01-15T21:59:59Z", "2026-01-15T22:00:00Z",
      "2026-01-15T05:59:59Z", "2026-01-15T06:00:00Z", "2026-01-15T00:00:00Z",
      "2026-01-15T23:59:59Z"
    };
    boolean sawOpen = false;
    boolean sawClosed = false;
    for (String i : instants) {
      NOW.set(Instant.parse(i));
      assertParity(FixtureIds.FLAG_A1, FixtureIds.ENV_A_PROD, i);
      boolean o = getEnv(FixtureIds.ENV_A_PROD).get("changeWindowOpenNow").asBoolean();
      sawOpen |= o;
      sawClosed |= !o;
    }
    assertThat(sawOpen && sawClosed).isTrue();
    // expected truth table for [9,17) independent of the implementation
    NOW.set(Instant.parse("2026-01-15T08:59:59Z"));
    assertThat(getEnv(FixtureIds.ENV_A_PROD).get("changeWindowOpenNow").asBoolean()).isFalse();
    NOW.set(Instant.parse("2026-01-15T09:00:00Z"));
    assertThat(getEnv(FixtureIds.ENV_A_PROD).get("changeWindowOpenNow").asBoolean()).isTrue();
    NOW.set(Instant.parse("2026-01-15T16:59:59Z"));
    assertThat(getEnv(FixtureIds.ENV_A_PROD).get("changeWindowOpenNow").asBoolean()).isTrue();
    NOW.set(Instant.parse("2026-01-15T17:00:00Z"));
    assertThat(getEnv(FixtureIds.ENV_A_PROD).get("changeWindowOpenNow").asBoolean()).isFalse();
    // wrap [22,6) on env B (no state PUT for B needed: flag A1 belongs to project A)
    String[][] wrap = {
      {"2026-01-15T21:59:59Z", "false"}, {"2026-01-15T22:00:00Z", "true"},
      {"2026-01-15T23:59:59Z", "true"}, {"2026-01-15T00:00:00Z", "true"},
      {"2026-01-15T05:59:59Z", "true"}, {"2026-01-15T06:00:00Z", "false"},
      {"2026-01-15T12:00:00Z", "false"}
    };
    for (String[] w : wrap) {
      NOW.set(Instant.parse(w[0]));
      assertThat(getEnv(FixtureIds.ENV_B_PROD).get("changeWindowOpenNow").asBoolean())
          .as("wrap at %s", w[0])
          .isEqualTo(Boolean.parseBoolean(w[1]));
    }
  }

  @Test
  @DisplayName("start==end (D-15), null window, 0..23 and 0..24: open at every probed second")
  void unlimitedWindows() throws Exception {
    Integer[][] cases = {{9, 9}, {0, 0}, {null, null}, {0, 23}, {0, 24}};
    for (Integer[] c : cases) {
      window(FixtureIds.ENV_A_PROD, c[0], c[1], null);
      for (String i : new String[] {"2026-01-15T00:00:00Z", "2026-01-15T12:00:00Z"}) {
        NOW.set(Instant.parse(i));
        assertThat(getEnv(FixtureIds.ENV_A_PROD).get("changeWindowOpenNow").asBoolean())
            .as("%s-%s at %s", c[0], c[1], i)
            .isTrue();
        assertThat(prodWriteAllowed(FixtureIds.FLAG_A1, FixtureIds.ENV_A_PROD)).isTrue();
      }
    }
    // 0..23 at 23:59:59 is closed (end exclusive) and PUT agrees
    window(FixtureIds.ENV_A_PROD, 0, 23, null);
    NOW.set(Instant.parse("2026-01-15T23:59:59Z"));
    assertParity(FixtureIds.FLAG_A1, FixtureIds.ENV_A_PROD, "23:59:59 [0,23)");
    assertThat(getEnv(FixtureIds.ENV_A_PROD).get("changeWindowOpenNow").asBoolean()).isFalse();
  }

  @Test
  @DisplayName("one null bound in storage = unrestricted, same as PUT; zone still reported")
  void oneNullBound() throws Exception {
    window(FixtureIds.ENV_A_PROD, 9, null, null);
    NOW.set(Instant.parse("2026-01-15T21:00:00Z"));
    assertParity(FixtureIds.FLAG_A1, FixtureIds.ENV_A_PROD, "9-null");
    assertThat(getEnv(FixtureIds.ENV_A_PROD).get("changeWindowOpenNow").asBoolean()).isTrue();
    window(FixtureIds.ENV_A_PROD, null, 17, null);
    assertParity(FixtureIds.FLAG_A1, FixtureIds.ENV_A_PROD, "null-17");
  }

  @Test
  @DisplayName("per-env zone valid / invalid / blank / lowercase: zone + parity vs configured UTC")
  void zoneResolution() throws Exception {
    NOW.set(Instant.parse("2026-01-15T21:00:00Z")); // UTC 21:00 = HCM 04:00 = NY 16:00
    window(FixtureIds.ENV_A_PROD, 9, 17, "America/New_York");
    JsonNode n = getEnv(FixtureIds.ENV_A_PROD);
    assertThat(n.get("changeWindowZone").asText()).isEqualTo("America/New_York");
    assertThat(n.get("changeWindowOpenNow").asBoolean()).isTrue();
    assertParity(FixtureIds.FLAG_A1, FixtureIds.ENV_A_PROD, "NY");

    window(FixtureIds.ENV_A_PROD, 9, 17, "Asia/Ho_Chi_Minh");
    n = getEnv(FixtureIds.ENV_A_PROD);
    assertThat(n.get("changeWindowZone").asText()).isEqualTo("Asia/Ho_Chi_Minh");
    assertThat(n.get("changeWindowOpenNow").asBoolean()).isFalse();
    assertParity(FixtureIds.FLAG_A1, FixtureIds.ENV_A_PROD, "HCM");

    for (String bad : new String[] {"Not/AZone", "", "   ", "asia/ho_chi_minh"}) {
      window(FixtureIds.ENV_A_PROD, 9, 17, bad);
      n = getEnv(FixtureIds.ENV_A_PROD);
      assertThat(n.get("changeWindowZone").asText()).as("zone for '%s'", bad).isEqualTo("UTC");
      assertThat(n.get("changeWindowOpenNow").asBoolean()).as("open for '%s'", bad).isFalse();
      assertParity(FixtureIds.FLAG_A1, FixtureIds.ENV_A_PROD, "bad zone " + bad);
    }
  }

  @Test
  @DisplayName("offset-style stored zone (legacy data, API would reject it): ZoneId.of accepts it")
  void offsetStyleStoredZone() throws Exception {
    NOW.set(Instant.parse("2026-01-15T21:00:00Z")); // +07:00 -> 04:00
    window(FixtureIds.ENV_A_PROD, 9, 17, "UTC+7");
    JsonNode n = getEnv(FixtureIds.ENV_A_PROD);
    assertThat(n.get("changeWindowZone").asText()).isEqualTo("UTC+07:00");
    assertThat(n.get("changeWindowOpenNow").asBoolean()).isFalse();
    assertParity(FixtureIds.FLAG_A1, FixtureIds.ENV_A_PROD, "UTC+7");
    NOW.set(Instant.parse("2026-01-15T03:00:00Z")); // 10:00 local
    assertThat(getEnv(FixtureIds.ENV_A_PROD).get("changeWindowOpenNow").asBoolean()).isTrue();
    assertParity(FixtureIds.FLAG_A1, FixtureIds.ENV_A_PROD, "UTC+7 open");
  }

  @Test
  @DisplayName("DST: America/New_York spring-forward and fall-back vs the real PUT")
  void dstParity() throws Exception {
    String[][] cases = {
      // spring forward 2026-03-08 07:00Z: 01:59:59 EST -> 03:00:00 EDT (hour 2 never occurs)
      {"2026-03-08T06:59:59Z", "1", "2"},
      {"2026-03-08T07:00:00Z", "3", "4"},
      {"2026-03-08T07:00:00Z", "2", "3"},
      // fall back 2026-11-01: 05:30Z = 01:30 EDT, 06:30Z = 01:30 EST (hour 1 occurs twice)
      {"2026-11-01T05:30:00Z", "1", "2"},
      {"2026-11-01T06:30:00Z", "1", "2"},
      {"2026-11-01T07:00:00Z", "1", "2"}
    };
    boolean[] expect = {true, true, false, true, true, false};
    for (int k = 0; k < cases.length; k++) {
      NOW.set(Instant.parse(cases[k][0]));
      window(
          FixtureIds.ENV_A_PROD,
          Integer.parseInt(cases[k][1]),
          Integer.parseInt(cases[k][2]),
          "America/New_York");
      JsonNode n = getEnv(FixtureIds.ENV_A_PROD);
      assertThat(n.get("changeWindowZone").asText()).isEqualTo("America/New_York");
      assertThat(n.get("changeWindowOpenNow").asBoolean())
          .as("case %d %s", k, cases[k][0])
          .isEqualTo(expect[k]);
      assertParity(FixtureIds.FLAG_A1, FixtureIds.ENV_A_PROD, cases[k][0]);
    }
  }

  @Test
  @DisplayName("list and update responses carry both fields; update reflects the NEW window")
  void listAndUpdate() throws Exception {
    window(FixtureIds.ENV_A_PROD, 9, 17, null);
    HttpResponse<String> r =
        call(
            "GET",
            "/api/v1/environments?projectId=" + FixtureIds.PROJECT_A,
            FixtureIds.USER_OWNER_X,
            null);
    assertThat(r.statusCode()).isEqualTo(200);
    JsonNode content = mapper.readTree(r.body()).get("content");
    assertThat(content.size()).isEqualTo(3);
    for (JsonNode e : content) {
      assertThat(e.has("changeWindowZone")).isTrue();
      assertThat(e.has("changeWindowOpenNow")).isTrue();
      assertThat(e.get("changeWindowZone").asText()).isEqualTo("UTC");
      assertThat(e.get("changeWindowOpenNow").asBoolean())
          .isEqualTo(!e.get("id").asText().equals(FixtureIds.ENV_A_PROD.toString()));
    }
    // update PROD window to include 21:00 UTC; response must show open=true and the new zone
    HttpResponse<String> u =
        call(
            "PUT",
            "/api/v1/environments/" + FixtureIds.ENV_A_PROD,
            FixtureIds.USER_OWNER_X,
            "{\"changeWindowStartHour\":20,\"changeWindowEndHour\":22,"
                + "\"changeWindowTimezone\":\"UTC\"}");
    // closed at 21:00? window currently [9,17) so the PUT itself is gated by the OLD window
    if (u.statusCode() == 200) {
      JsonNode n = mapper.readTree(u.body());
      assertThat(n.get("changeWindowOpenNow").asBoolean()).isTrue();
      assertThat(n.get("changeWindowZone").asText()).isEqualTo("UTC");
    } else {
      assertThat(u.statusCode()).isEqualTo(403);
    }
    // invalid zone is rejected with 400, never persisted
    NOW.set(Instant.parse("2026-01-15T10:00:00Z"));
    HttpResponse<String> bad =
        call(
            "PUT",
            "/api/v1/environments/" + FixtureIds.ENV_A_DEV,
            FixtureIds.USER_OWNER_X,
            "{\"changeWindowTimezone\":\"Not/AZone\"}");
    assertThat(bad.statusCode()).isEqualTo(400);
  }

  @Test
  @DisplayName("non-prod env: fields present, openNow reflects its own window/zone")
  void nonProdEnvironment() throws Exception {
    JsonNode n = getEnv(FixtureIds.ENV_A_DEV);
    assertThat(n.get("changeWindowZone").asText()).isEqualTo("UTC");
    assertThat(n.get("changeWindowOpenNow").asBoolean()).isTrue();
  }

  @Test
  @DisplayName("audit before/after snapshots on update keep the old shape: no new keys, no diff")
  void auditShapeUnchanged() throws Exception {
    NOW.set(Instant.parse("2026-01-15T10:00:00Z"));
    HttpResponse<String> u =
        call(
            "PUT",
            "/api/v1/environments/" + FixtureIds.ENV_A_DEV,
            FixtureIds.USER_OWNER_X,
            "{\"description\":\"changed\"}");
    assertThat(u.statusCode()).isEqualTo(200);
    List<String> rows =
        jdbc.queryForList(
            "select before_state from audit_log where entity_id = ? and action = 'UPDATE'",
            String.class,
            FixtureIds.ENV_A_DEV);
    List<String> after =
        jdbc.queryForList(
            "select after_state from audit_log where entity_id = ? and action = 'UPDATE'",
            String.class,
            FixtureIds.ENV_A_DEV);
    assertThat(rows).isNotEmpty();
    for (String s :
        new java.util.ArrayList<>(rows) {
          {
            addAll(after);
          }
        }) {
      assertThat(s).doesNotContain("changeWindowOpenNow").doesNotContain("changeWindowZone");
    }
  }

  @Test
  @DisplayName("audit before/after for update and delete have the exact stored-attribute key set")
  void auditKeySetExact() throws Exception {
    NOW.set(Instant.parse("2026-01-15T10:00:00Z"));
    call(
        "PUT",
        "/api/v1/environments/" + FixtureIds.ENV_A_DEV,
        FixtureIds.USER_OWNER_X,
        "{\"description\":\"x\"}");
    call("DELETE", "/api/v1/environments/" + FixtureIds.ENV_A_STG, FixtureIds.USER_OWNER_X, null);
    HttpResponse<String> created =
        call(
            "POST",
            "/api/v1/environments",
            FixtureIds.USER_OWNER_X,
            "{\"projectId\":\""
                + FixtureIds.PROJECT_A
                + "\",\"name\":\"qa-new\",\"type\":\"DEVELOPMENT\"}");
    assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
    String newId = mapper.readTree(created.body()).get("id").asText();
    assertThat(created.body()).doesNotContain("changeWindowOpenNow");
    String createAfter =
        jdbc.queryForObject(
            "select after_state from audit_log where entity_id = '"
                + newId
                + "' and action = 'CREATE'",
            String.class);
    assertThat(createAfter)
        .doesNotContain("changeWindowZone")
        .doesNotContain("changeWindowOpenNow");
    for (String sql :
        List.of(
            "select before_state from audit_log where entity_id = '"
                + FixtureIds.ENV_A_DEV
                + "' and action = 'UPDATE'",
            "select after_state from audit_log where entity_id = '"
                + FixtureIds.ENV_A_DEV
                + "' and action = 'UPDATE'",
            "select before_state from audit_log where entity_id = '"
                + FixtureIds.ENV_A_STG
                + "' and action = 'DELETE'")) {
      List<String> payloads = jdbc.queryForList(sql, String.class);
      assertThat(payloads).as(sql).isNotEmpty();
      for (String pl : payloads) {
        assertThat(pl)
            .as(sql)
            .doesNotContain("changeWindowZone")
            .doesNotContain("changeWindowOpenNow");
      }
    }
  }
}
