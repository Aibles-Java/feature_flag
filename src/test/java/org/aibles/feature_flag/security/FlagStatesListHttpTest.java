package org.aibles.feature_flag.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.aibles.feature_flag.domain.entity.User;
import org.aibles.feature_flag.testsupport.FixedClocksTestConfig;
import org.aibles.feature_flag.testsupport.FixtureIds;
import org.aibles.feature_flag.testsupport.SyntheticFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * S-2.6 independent QA at HTTP level: real servlet container, real JWT filter chain, real PDP, real
 * advice. Complements the service-level {@link FlagStatesListIntegrationTest}.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.allow-bean-definition-overriding=true",
      "spring.datasource.url=jdbc:h2:mem:flagstateshttp-testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;"
          + "MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=KEY,VALUE;"
          + "CASE_INSENSITIVE_IDENTIFIERS=TRUE"
    })
@ActiveProfiles("test")
@Import(FixedClocksTestConfig.class)
class FlagStatesListHttpTest {

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
  }

  private HttpResponse<String> get(String flagId, UUID user) throws Exception {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(
                URI.create(
                    "http://localhost:" + port + "/api/v1/flags/" + flagId + "/environments"))
            .timeout(Duration.ofSeconds(30))
            .GET();
    if (user != null) {
      String email =
          jdbc.queryForObject("select email from users where id = ?", String.class, user);
      b.header(
          "Authorization",
          "Bearer "
              + jwt.generateToken(
                  UserPrincipal.from(
                      User.builder().id(user).email(email).passwordHash("x").build())));
    }
    return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  private List<String> envIds(HttpResponse<String> r) throws Exception {
    List<String> out = new ArrayList<>();
    for (JsonNode n : mapper.readTree(r.body())) out.add(n.get("environmentId").asText());
    return out;
  }

  private long audits() {
    return jdbc.queryForObject("select count(*) from audit_log", Long.class);
  }

  @Test
  @DisplayName(
      "200 for VIEWER (FLAG_READ): all states with version, rolloutPercent, lastEvaluatedAt")
  void viewerGetsAllStates() throws Exception {
    long before = audits();
    HttpResponse<String> r = get(FixtureIds.FLAG_A1.toString(), FixtureIds.USER_VIEWER_X);
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(envIds(r))
        .containsExactlyInAnyOrder(
            FixtureIds.ENV_A_DEV.toString(),
            FixtureIds.ENV_A_STG.toString(),
            FixtureIds.ENV_A_PROD.toString());
    for (JsonNode n : mapper.readTree(r.body())) {
      assertThat(n.has("version")).isTrue();
      assertThat(n.get("version").isNull()).isFalse();
      assertThat(n.has("rolloutPercent")).isTrue();
      assertThat(n.has("lastEvaluatedAt")).isTrue();
      assertThat(n.get("flagId").asText()).isEqualTo(FixtureIds.FLAG_A1.toString());
    }
    assertThat(audits()).isEqualTo(before);
  }

  @Test
  @DisplayName("grant-only user (project A grant) gets 200")
  void grantUserOk() throws Exception {
    assertThat(get(FixtureIds.FLAG_A1.toString(), FixtureIds.USER_GRANT_A).statusCode())
        .isEqualTo(200);
  }

  @Test
  @DisplayName("403 for same-org-no-grant, other-org member, no-access user")
  void forbidden() throws Exception {
    for (UUID u :
        List.of(FixtureIds.USER_OUTSIDER_X, FixtureIds.USER_MEMBER_Y, FixtureIds.USER_NOACCESS)) {
      HttpResponse<String> r = get(FixtureIds.FLAG_A1.toString(), u);
      assertThat(r.statusCode()).as("user %s", u).isEqualTo(403);
      assertThat(r.body()).doesNotContain(FixtureIds.ENV_A_DEV.toString());
    }
  }

  @Test
  @DisplayName("401 without a token")
  void unauthenticated() throws Exception {
    assertThat(get(FixtureIds.FLAG_A1.toString(), null).statusCode()).isEqualTo(401);
  }

  @Test
  @DisplayName("404 for unknown flag")
  void unknownFlag() throws Exception {
    assertThat(get(UUID.randomUUID().toString(), FixtureIds.USER_OWNER_X).statusCode())
        .isEqualTo(404);
  }

  @Test
  @DisplayName("non-UUID flagId -> 400 (not 500)")
  void nonUuidIs400() throws Exception {
    HttpResponse<String> r = get("not-a-uuid", FixtureIds.USER_OWNER_X);
    assertThat(r.statusCode()).isEqualTo(400);
  }

  @Test
  @DisplayName("cross-project anomalous state row is never returned over HTTP")
  void crossProjectRowHidden() throws Exception {
    jdbc.update(
        "insert into flag_environment_states (id, feature_flag_id, environment_id, enabled,"
            + " value, rollout_percent, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?)",
        FixtureIds.stateId(FixtureIds.FLAG_A1, FixtureIds.ENV_B_PROD),
        FixtureIds.FLAG_A1,
        FixtureIds.ENV_B_PROD,
        true,
        "true",
        100,
        SyntheticFixture.CLOCK.instant().atZone(java.time.ZoneOffset.UTC).toLocalDateTime(),
        SyntheticFixture.CLOCK.instant().atZone(java.time.ZoneOffset.UTC).toLocalDateTime());
    HttpResponse<String> r = get(FixtureIds.FLAG_A1.toString(), FixtureIds.USER_OWNER_X);
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(envIds(r)).hasSize(3).doesNotContain(FixtureIds.ENV_B_PROD.toString());
  }

  @Test
  @DisplayName(
      "project comes from the flag: flag of project B is 403 for project-A-only grant user")
  void projectDerivedFromFlag() throws Exception {
    assertThat(get(FixtureIds.FLAG_B1.toString(), FixtureIds.USER_GRANT_A).statusCode())
        .isEqualTo(403);
    HttpResponse<String> r = get(FixtureIds.FLAG_B1.toString(), FixtureIds.USER_OWNER_X);
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(envIds(r)).doesNotContain(FixtureIds.ENV_A_DEV.toString());
  }

  @Test
  @DisplayName("deterministic order: createdAt then id, stable across calls")
  void deterministicOrder() throws Exception {
    // make creation order differ from id order: PROD oldest, DEV newest
    jdbc.update(
        "update environments set created_at = ? where id = ?",
        java.sql.Timestamp.valueOf("2020-01-01 00:00:00"),
        FixtureIds.ENV_A_PROD);
    jdbc.update(
        "update environments set created_at = ? where id = ?",
        java.sql.Timestamp.valueOf("2020-01-02 00:00:00"),
        FixtureIds.ENV_A_STG);
    jdbc.update(
        "update environments set created_at = ? where id = ?",
        java.sql.Timestamp.valueOf("2020-01-03 00:00:00"),
        FixtureIds.ENV_A_DEV);
    List<String> expected =
        List.of(
            FixtureIds.ENV_A_PROD.toString(),
            FixtureIds.ENV_A_STG.toString(),
            FixtureIds.ENV_A_DEV.toString());
    for (int i = 0; i < 3; i++) {
      assertThat(envIds(get(FixtureIds.FLAG_A1.toString(), FixtureIds.USER_OWNER_X)))
          .containsExactlyElementsOf(expected);
    }
  }

  @Test
  @DisplayName("archived flag: states are still listed (documented behaviour)")
  void archivedFlagStillListed() throws Exception {
    jdbc.update("update feature_flags set archived = true where id = ?", FixtureIds.FLAG_A1);
    HttpResponse<String> r = get(FixtureIds.FLAG_A1.toString(), FixtureIds.USER_VIEWER_X);
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(envIds(r)).hasSize(3);
  }
}
