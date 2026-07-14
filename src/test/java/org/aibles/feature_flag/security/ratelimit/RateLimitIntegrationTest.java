package org.aibles.feature_flag.security.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import java.util.UUID;
import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.domain.entity.Organization;
import org.aibles.feature_flag.domain.entity.Project;
import org.aibles.feature_flag.repository.EnvironmentRepository;
import org.aibles.feature_flag.repository.OrganizationRepository;
import org.aibles.feature_flag.repository.ProjectRepository;
import org.aibles.feature_flag.util.ApiKeyHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

/**
 * Integration tests for the rate limiter (issue #26), exercising both security chains through the
 * real filter stack. Rate limiting is enabled here with a low capacity (2) via test properties,
 * overriding the {@code app.rate-limit.enabled=false} default of the test profile.
 *
 * <p>Each test uses a distinct bucket key (a unique client IP, or a freshly persisted environment)
 * so the shared in-memory buckets don't collide between test methods.
 */
@SpringBootTest(
    properties = {
      // Distinct @SpringBootTest properties spin up a SECOND application context; give it its own
      // in-memory H2 DB so its Liquibase run doesn't collide with the shared `testdb` used by the
      // other integration tests ("DATABASECHANGELOG already exists"). See rate-limit test notes
      // (#26).
      "spring.datasource.url=jdbc:h2:mem:ratelimit-testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
      "app.rate-limit.enabled=true",
      "app.rate-limit.auth.capacity=2",
      "app.rate-limit.auth.refill-period=1m",
      "app.rate-limit.sdk.capacity=2",
      "app.rate-limit.sdk.refill-period=1m"
    })
@ActiveProfiles("test")
class RateLimitIntegrationTest {

  private static final String AUTH_LOGIN = "/api/v1/auth/login";
  private static final String SDK_ENDPOINT = "/api/v1/sdk/flags";
  private static final int CAPACITY = 2;

  @Autowired private WebApplicationContext webApplicationContext;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private ProjectRepository projectRepository;
  @Autowired private EnvironmentRepository environmentRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
  }

  // --- Admin/auth chain: per-IP limit on /api/v1/auth/** -------------------

  @Test
  void authLoginIsRateLimitedPerIpWith429AndRetryAfter() throws Exception {
    String clientIp = "203.0.113." + (int) (UUID.randomUUID().getLeastSignificantBits() & 0xFF);
    String body = "{\"email\":\"nobody@example.com\",\"password\":\"whatever\"}";

    // First `capacity` requests are not rate-limited (they fail auth, but never with 429).
    for (int i = 0; i < CAPACITY; i++) {
      int status =
          mockMvc
              .perform(
                  post(AUTH_LOGIN)
                      .with(
                          r -> {
                            r.setRemoteAddr(clientIp);
                            return r;
                          })
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(body))
              .andReturn()
              .getResponse()
              .getStatus();
      assertThat(status).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    // The next request from the same IP is throttled.
    mockMvc
        .perform(
            post(AUTH_LOGIN)
                .with(
                    r -> {
                      r.setRemoteAddr(clientIp);
                      return r;
                    })
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"));
  }

  // --- SDK chain: per-API-key limit on /api/v1/sdk/** ----------------------

  @Test
  void sdkEvaluationIsRateLimitedPerKeyWith429AndRetryAfter() throws Exception {
    String apiKey = persistEnvironmentWithApiKey();

    // First `capacity` requests pass the limiter (they may 500 on the unrelated H2 `key`
    // column quirk, but never 429).
    for (int i = 0; i < CAPACITY; i++) {
      int status =
          mockMvc
              .perform(get(SDK_ENDPOINT).header("X-Environment-Key", apiKey))
              .andReturn()
              .getResponse()
              .getStatus();
      assertThat(status).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    // The next request with the same key is throttled.
    mockMvc
        .perform(get(SDK_ENDPOINT).header("X-Environment-Key", apiKey))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"));
  }

  /** Persists a minimal Org → Project → Environment chain and returns the environment's API key. */
  private String persistEnvironmentWithApiKey() {
    String unique = UUID.randomUUID().toString();
    Organization org =
        organizationRepository.save(
            Organization.builder().name("org-" + unique).slug("slug-" + unique).build());
    Project project =
        projectRepository.save(
            Project.builder().organization(org).name("project-" + unique).build());
    // Store only the SHA-256 hash (issue #24); the plaintext key is what the SDK sends in the
    // header.
    String apiKey = "ratelimit-key-" + unique;
    environmentRepository.save(
        Environment.builder()
            .project(project)
            .name("env-" + unique)
            .apiKeyHash(ApiKeyHasher.hash(apiKey))
            .build());
    return apiKey;
  }
}
