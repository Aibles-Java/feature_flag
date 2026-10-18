package org.aibles.feature_flag.flags;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

import io.github.bucket4j.TimeMeter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.aibles.feature_flag.domain.entity.User;
import org.aibles.feature_flag.security.JwtTokenProvider;
import org.aibles.feature_flag.security.UserPrincipal;
import org.aibles.feature_flag.service.FlagMatrixService;
import org.aibles.feature_flag.testsupport.FixtureIds;
import org.aibles.feature_flag.testsupport.ManualTimeMeter;
import org.aibles.feature_flag.testsupport.SyntheticFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * S-2.10 adversarial tests over a real Tomcat: once a user's matrix bucket is exhausted, no
 * URL/verb variant may reach the matrix service (bypass), and concurrency must yield exactly
 * capacity successes. Time is a manual meter.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:matrix-bypass-testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;"
          + "MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=KEY,VALUE;"
          + "CASE_INSENSITIVE_IDENTIFIERS=TRUE",
      "app.rate-limit.enabled=true",
      "app.rate-limit.matrix.capacity=60",
      "app.rate-limit.matrix.refill-period=1m"
    })
@ActiveProfiles("test")
@Import(FlagMatrixRateLimitBypassHttpTest.TimeConfig.class)
class FlagMatrixRateLimitBypassHttpTest {

  private static final ManualTimeMeter TIME = new ManualTimeMeter();

  @TestConfiguration
  static class TimeConfig {
    @Bean
    @Primary
    TimeMeter bypassTimeMeter() {
      return TIME;
    }
  }

  @LocalServerPort int port;
  @Autowired JwtTokenProvider jwt;
  @Autowired JdbcTemplate jdbc;
  @MockitoSpyBean FlagMatrixService matrixService;
  final HttpClient http = HttpClient.newHttpClient();

  @BeforeEach
  void setUp() {
    SyntheticFixture.load(jdbc);
    TIME.advance(Duration.ofMinutes(10));
    clearInvocations(matrixService);
  }

  private String bearer(UUID user) {
    String email = jdbc.queryForObject("select email from users where id = ?", String.class, user);
    return "Bearer "
        + jwt.generateToken(
            UserPrincipal.from(User.builder().id(user).email(email).passwordHash("x").build()));
  }

  private HttpResponse<String> send(String method, String pathAndQuery, UUID user, String... hdrs)
      throws Exception {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + pathAndQuery))
            .method(method, HttpRequest.BodyPublishers.noBody());
    if (user != null) b.header("Authorization", bearer(user));
    for (int i = 0; i < hdrs.length; i += 2) b.header(hdrs[i], hdrs[i + 1]);
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  private int get(String pathAndQuery, UUID user) throws Exception {
    return send("GET", pathAndQuery, user).statusCode();
  }

  private String q() {
    return "projectId=" + FixtureIds.PROJECT_A;
  }

  private static final String P = "/api/v1/flags/environment-states";

  @Test
  @DisplayName("exactly 60 of 100 parallel requests from one user succeed")
  void concurrencyExactlyCapacity() throws Exception {
    ExecutorService ex = Executors.newFixedThreadPool(32);
    List<Callable<Integer>> tasks = new ArrayList<>();
    for (int i = 0; i < 100; i++) tasks.add(() -> get(P + "?" + q(), FixtureIds.USER_OWNER_X));
    int ok = 0, tooMany = 0, other = 0;
    for (Future<Integer> f : ex.invokeAll(tasks)) {
      int s = f.get();
      if (s == 200) ok++;
      else if (s == 429) tooMany++;
      else other++;
    }
    ex.shutdownNow();
    assertThat(ok).isEqualTo(60);
    assertThat(tooMany).isEqualTo(40);
    assertThat(other).isZero();
  }

  @Test
  @DisplayName("no URL or verb variant reaches the matrix service once the bucket is exhausted")
  void noBypassVariant() throws Exception {
    for (int i = 0; i < 60; i++) {
      assertThat(get(P + "?" + q(), FixtureIds.USER_OWNER_X)).isEqualTo(200);
    }
    assertThat(get(P + "?" + q(), FixtureIds.USER_OWNER_X)).isEqualTo(429);
    clearInvocations(matrixService);

    Map<String, Integer> results = new LinkedHashMap<>();
    UUID u = FixtureIds.USER_OWNER_X;
    String[][] variants = {
      {"GET", P + "/?" + q()},
      {"GET", "/api/v1/flags//environment-states?" + q()},
      {"GET", P + ";jsessionid=x?" + q()},
      {"GET", "/API/V1/FLAGS/ENVIRONMENT-STATES?" + q()},
      {"GET", "/api/v1/flags/%65nvironment-states?" + q()},
      {"GET", "/api/v1/%66lags/environment-states?" + q()},
      {"GET", "/api/v1/flags/environment%2Dstates?" + q()},
      {"GET", "/api/v1/flags/environment-states%2f?" + q()},
      {"GET", "/api/v1/flags/./environment-states?" + q()},
      {"GET", "/api/v1/flags/x/../environment-states?" + q()},
      {"GET", P + ".json?" + q()},
      {"GET", P + "?" + q() + "&page=1&size=5"},
      {"GET", P + "?" + q() + "&page=0&size=1"},
      {"GET", P + "?" + q() + "&x=%0a"},
      {"HEAD", P + "?" + q()},
      {"OPTIONS", P + "?" + q()},
    };
    for (String[] v : variants) {
      results.put(v[0] + " " + v[1], send(v[0], v[1], u).statusCode());
    }
    results.put(
        "POST+X-HTTP-Method-Override: GET",
        send("POST", P + "?" + q(), u, "X-HTTP-Method-Override", "GET").statusCode());
    results.put(
        "GET+X-HTTP-Method-Override: HEAD",
        send("GET", P + "?" + q(), u, "X-HTTP-Method-Override", "HEAD").statusCode());
    System.out.println("BYPASS-RESULTS " + results);
    // The decisive check: none of those may have executed the matrix.
    verifyNoInteractions(matrixService);
    // OPTIONS is answered by the CORS layer (200, no matrix work: verified above); every other
    // variant must be refused or unrouted.
    results.entrySet().stream()
        .filter(e -> !e.getKey().startsWith("OPTIONS"))
        .forEach(e -> assertThat(e.getValue()).as(e.getKey()).isNotEqualTo(200));
  }

  @Test
  @DisplayName("a token for another user has its own bucket; unauth is 401 and not bucketed")
  void otherUserAndUnauth() throws Exception {
    for (int i = 0; i < 61; i++) get(P + "?" + q(), FixtureIds.USER_OWNER_X);
    assertThat(get(P + "?" + q(), FixtureIds.USER_OWNER_X)).isEqualTo(429);
    assertThat(get(P + "?" + q(), FixtureIds.USER_ADMIN_X)).isEqualTo(200);
    for (int i = 0; i < 70; i++) assertThat(get(P + "?" + q(), null)).isEqualTo(401);
  }

  @Test
  @DisplayName("HEAD shares the GET bucket: 61st HEAD is 429 and does no matrix work")
  void headSharesBucket() throws Exception {
    for (int i = 0; i < 30; i++) {
      assertThat(get(P + "?" + q(), FixtureIds.USER_OWNER_X)).isEqualTo(200);
      assertThat(send("HEAD", P + "?" + q(), FixtureIds.USER_OWNER_X).statusCode()).isEqualTo(200);
    }
    clearInvocations(matrixService);
    assertThat(send("HEAD", P + "?" + q(), FixtureIds.USER_OWNER_X).statusCode()).isEqualTo(429);
    assertThat(get(P + "?" + q(), FixtureIds.USER_OWNER_X)).isEqualTo(429);
    verifyNoInteractions(matrixService);
  }
}
