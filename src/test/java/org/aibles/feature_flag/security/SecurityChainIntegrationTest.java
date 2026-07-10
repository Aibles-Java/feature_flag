package org.aibles.feature_flag.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import java.util.UUID;
import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.domain.entity.Organization;
import org.aibles.feature_flag.domain.entity.Project;
import org.aibles.feature_flag.domain.entity.User;
import org.aibles.feature_flag.repository.EnvironmentRepository;
import org.aibles.feature_flag.repository.OrganizationRepository;
import org.aibles.feature_flag.repository.ProjectRepository;
import org.aibles.feature_flag.util.ApiKeyHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

/**
 * Full-context tests that exercise the two {@link
 * org.springframework.security.web.SecurityFilterChain} beans wired in {@code SecurityConfig}: the
 * SDK API-key chain (order=1) and the Admin JWT chain (order=2). Focused on rejection and the key
 * invariant that credentials for one chain cannot authenticate against the other.
 */
@SpringBootTest
@ActiveProfiles("test")
class SecurityChainIntegrationTest {

  private static final String ADMIN_ENDPOINT = "/api/v1/organisations";
  private static final String SDK_ENDPOINT = "/api/v1/sdk/flags";

  @Autowired private WebApplicationContext webApplicationContext;

  @Autowired private JwtTokenProvider jwtTokenProvider;

  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private ProjectRepository projectRepository;
  @Autowired private EnvironmentRepository environmentRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    // Build MockMvc from the full context with the real Spring Security filter chains
    // applied, so both the SDK (order=1) and Admin (order=2) chains are exercised.
    mockMvc = webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
  }

  /** A cryptographically valid Admin JWT for a user that is not persisted in the DB. */
  private String validJwtForUnknownUser() {
    UserPrincipal principal =
        UserPrincipal.from(
            User.builder()
                .id(UUID.randomUUID())
                .email("ghost-" + UUID.randomUUID() + "@example.com")
                .passwordHash("x")
                .build());
    return jwtTokenProvider.generateToken(principal);
  }

  // --- Admin chain (JWT) ---------------------------------------------------

  @Test
  void adminEndpointRejectsMissingBearerToken() throws Exception {
    mockMvc.perform(get(ADMIN_ENDPOINT)).andExpect(status().isForbidden());
  }

  @Test
  void adminEndpointRejectsInvalidBearerToken() throws Exception {
    mockMvc
        .perform(get(ADMIN_ENDPOINT).header("Authorization", "Bearer not-a-real-token"))
        .andExpect(status().isForbidden());
  }

  @Test
  void adminValidTokenForDeletedUser_returnsForbidden() throws Exception {
    String token = validJwtForUnknownUser();
    mockMvc
        .perform(get(ADMIN_ENDPOINT).header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden());
  }

  // --- SDK chain (API key) -------------------------------------------------

  @Test
  void sdkEndpointRejectsMissingApiKey() throws Exception {
    mockMvc.perform(get(SDK_ENDPOINT)).andExpect(status().isUnauthorized());
  }

  @Test
  void sdkEndpointRejectsInvalidApiKey() throws Exception {
    mockMvc
        .perform(get(SDK_ENDPOINT).header("X-Environment-Key", "bogus-key"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void sdkEndpointAcceptsValidApiKeyAuthenticatedAgainstStoredHash() throws Exception {
    // Persist an environment storing only the SHA-256 hash of the key, then present the
    // plaintext in the header. The API-key filter must authenticate against the hash at rest
    // and hand off to the SDK controller — i.e. it must NOT reject with the filter's 401.
    // (We assert on "not rejected" rather than 200 because the evaluation query itself hits
    // an unrelated H2-only quirk with the reserved-word `key` column — see #24 PR notes.)
    String plaintextKey = "sdk-plaintext-" + UUID.randomUUID();
    persistEnvironmentWithApiKeyHash(ApiKeyHasher.hash(plaintextKey));

    // Authentication succeeded: the request got past the API-key filter (no 401).
    assertThat(statusFor(plaintextKey)).isNotEqualTo(HttpStatus.UNAUTHORIZED.value());
  }

  @Test
  void rotatingApiKeyInvalidatesOldKeyAndAcceptsNewOne() throws Exception {
    // The core rotation guarantee, exercised end-to-end through the real SDK auth path
    // (findByApiKeyHash): once the stored hash is replaced, the old plaintext can no longer
    // authenticate and only the new plaintext does. (The service's rotate logic itself is
    // unit-tested in EnvironmentServiceImplTest; here we drive the actual filter.)
    String oldKey = "old-" + UUID.randomUUID();
    String newKey = "new-" + UUID.randomUUID();
    Environment env = persistEnvironmentWithApiKeyHash(ApiKeyHasher.hash(oldKey));

    // Before rotation the old key authenticates.
    assertThat(statusFor(oldKey)).isNotEqualTo(HttpStatus.UNAUTHORIZED.value());

    // Rotate: the stored hash is replaced with the new key's hash (as rotateApiKey does).
    env.setApiKeyHash(ApiKeyHasher.hash(newKey));
    environmentRepository.save(env);

    // The old key is now rejected; the new key authenticates.
    assertThat(statusFor(oldKey)).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    assertThat(statusFor(newKey)).isNotEqualTo(HttpStatus.UNAUTHORIZED.value());
  }

  // --- Actuator health (issue #25) ----------------------------------------

  @Test
  void actuatorHealthIsReachableAnonymously() throws Exception {
    // No JWT, no API key — load balancers / probes must reach it. DB is up (H2) so 200 UP.
    mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }

  @Test
  void actuatorLivenessAndReadinessAreReachableAnonymously() throws Exception {
    mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
  }

  @Test
  void otherActuatorEndpointsAreNotAnonymouslyExposed() throws Exception {
    // Everything under /actuator except health/** falls through to authenticated() → 403 without a
    // JWT.
    mockMvc.perform(get("/actuator/info")).andExpect(status().isForbidden());
  }

  /** Presents {@code apiKey} on the SDK chain and returns the raw HTTP status. */
  private int statusFor(String apiKey) throws Exception {
    return mockMvc
        .perform(get(SDK_ENDPOINT).header("X-Environment-Key", apiKey))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  /** Builds a minimal Org → Project → Environment chain so the SDK filter can resolve the key. */
  private Environment persistEnvironmentWithApiKeyHash(String apiKeyHash) {
    String unique = UUID.randomUUID().toString();
    Organization org =
        organizationRepository.save(
            Organization.builder().name("org-" + unique).slug("slug-" + unique).build());
    Project project =
        projectRepository.save(
            Project.builder().organization(org).name("project-" + unique).build());
    return environmentRepository.save(
        Environment.builder()
            .project(project)
            .name("env-" + unique)
            .apiKeyHash(apiKeyHash)
            .build());
  }

  // --- Cross-chain isolation ----------------------------------------------

  @Test
  void apiKeyCannotAuthenticateAgainstAdminChain() throws Exception {
    // The admin chain ignores X-Environment-Key entirely; with no valid JWT this is rejected.
    mockMvc
        .perform(get(ADMIN_ENDPOINT).header("X-Environment-Key", "bogus-key"))
        .andExpect(status().isForbidden());
  }

  @Test
  void validJwtCannotAuthenticateAgainstSdkChain() throws Exception {
    // A cryptographically valid Admin credential must NOT grant SDK access: the SDK
    // chain only honours X-Environment-Key, so the Bearer token is ignored and the
    // request is rejected with the API-key filter's 401.
    mockMvc
        .perform(get(SDK_ENDPOINT).header("Authorization", "Bearer " + validJwtForUnknownUser()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void bearerPlusBogusKeyOnSdkChainIsGovernedByKeyCheck() throws Exception {
    // Even with a Bearer token present, the SDK chain evaluates only the (invalid) key.
    mockMvc
        .perform(
            get(SDK_ENDPOINT)
                .header("Authorization", "Bearer " + validJwtForUnknownUser())
                .header("X-Environment-Key", "bogus-key"))
        .andExpect(status().isUnauthorized());
  }
}
