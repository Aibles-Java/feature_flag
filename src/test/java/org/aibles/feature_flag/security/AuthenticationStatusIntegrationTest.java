package org.aibles.feature_flag.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Runs against a <strong>real servlet container</strong> rather than MockMvc, because the behaviour
 * under test only exists there.
 *
 * <p>MockMvc does not perform the container's {@code /error} forward. That dispatch is exactly what
 * makes the admin chain — which has no {@code securityMatcher} and is therefore the catch-all —
 * re-handle a request the management chain already answered. A MockMvc version of the challenge
 * assertion below passes whether or not the fix is present, i.e. it is vacuous; this one is not. It
 * was written after diffing a running instance against {@code develop} surfaced the regression.
 *
 * <p>Uses the JDK HTTP client rather than {@code TestRestTemplate} to stay clear of Spring Boot's
 * test-client package reshuffling, and takes the port from {@code local.server.port} for the same
 * reason.
 *
 * <p>Gets its own H2 database: a distinct {@code @SpringBootTest} configuration forks a second
 * application context, and sharing {@code mem:testdb} would re-run Liquibase against a schema that
 * already exists.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:authstatusdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
    })
@ActiveProfiles("test")
class AuthenticationStatusIntegrationTest {

  @Value("${local.server.port}")
  private int port;

  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(10))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  private HttpResponse<String> get(String path) throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private String challenge(HttpResponse<String> response) {
    return response.headers().firstValue("WWW-Authenticate").orElse("");
  }

  /**
   * The point of the change: unauthenticated is 401, not 403. A client cannot otherwise tell an
   * expired session from a permission denial, so a token-refresh interceptor never fires.
   */
  @Test
  void unauthenticatedAdminRequestReturns401WithBearerChallenge() throws Exception {
    HttpResponse<String> response = get("/api/v1/organisations");

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(challenge(response)).isEqualTo("Bearer");
    assertThat(response.headers().firstValue("Content-Type").orElse(""))
        .contains("application/problem+json");
    assertThat(response.body()).contains("\"status\":401").contains("\"title\":\"Unauthorized\"");
  }

  /**
   * Regression guard. The management chain answers with a Basic challenge; the ensuing error
   * dispatch re-enters the admin chain, where an unconditional header write would replace {@code
   * Basic} with {@code Bearer} and point a Prometheus scraper at the wrong scheme.
   */
  @Test
  void managementChallengeIsNotOverwrittenByTheAdminChain() throws Exception {
    HttpResponse<String> response = get("/actuator/info");

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(challenge(response)).startsWith("Basic");
  }

  /** The SDK chain rejects on its own, and must be unaffected by the admin chain's entry point. */
  @Test
  void sdkChainStillReturnsItsOwn401() throws Exception {
    assertThat(get("/api/v1/sdk/flags").statusCode()).isEqualTo(401);
  }

  /** Health probes stay anonymous — the fix must not close them. */
  @Test
  void healthProbeStaysPublic() throws Exception {
    assertThat(get("/actuator/health").statusCode()).isEqualTo(200);
  }
}
