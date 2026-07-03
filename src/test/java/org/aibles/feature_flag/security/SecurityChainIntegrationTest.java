package org.aibles.feature_flag.security;

import org.aibles.feature_flag.domain.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * Full-context tests that exercise the two {@link org.springframework.security.web.SecurityFilterChain}
 * beans wired in {@code SecurityConfig}: the SDK API-key chain (order=1) and the Admin
 * JWT chain (order=2). Focused on rejection and the key invariant that credentials for
 * one chain cannot authenticate against the other.
 */
@SpringBootTest
@ActiveProfiles("test")
class SecurityChainIntegrationTest {

    private static final String ADMIN_ENDPOINT = "/api/v1/organisations";
    private static final String SDK_ENDPOINT = "/api/v1/sdk/flags";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // Build MockMvc from the full context with the real Spring Security filter chains
        // applied, so both the SDK (order=1) and Admin (order=2) chains are exercised.
        mockMvc = webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    /** A cryptographically valid Admin JWT for a user that is not persisted in the DB. */
    private String validJwtForUnknownUser() {
        UserPrincipal principal = UserPrincipal.from(User.builder()
                .id(UUID.randomUUID())
                .email("ghost-" + UUID.randomUUID() + "@example.com")
                .passwordHash("x")
                .build());
        return jwtTokenProvider.generateToken(principal);
    }

    // --- Admin chain (JWT) ---------------------------------------------------

    @Test
    void adminEndpointRejectsMissingBearerToken() throws Exception {
        mockMvc.perform(get(ADMIN_ENDPOINT))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminEndpointRejectsInvalidBearerToken() throws Exception {
        mockMvc.perform(get(ADMIN_ENDPOINT).header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminValidTokenForDeletedUser_returnsForbidden() throws Exception {
        String token = validJwtForUnknownUser();
        mockMvc.perform(get(ADMIN_ENDPOINT).header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    // --- SDK chain (API key) -------------------------------------------------

    @Test
    void sdkEndpointRejectsMissingApiKey() throws Exception {
        mockMvc.perform(get(SDK_ENDPOINT))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void sdkEndpointRejectsInvalidApiKey() throws Exception {
        mockMvc.perform(get(SDK_ENDPOINT).header("X-Environment-Key", "bogus-key"))
                .andExpect(status().isUnauthorized());
    }

    // --- Cross-chain isolation ----------------------------------------------

    @Test
    void apiKeyCannotAuthenticateAgainstAdminChain() throws Exception {
        // The admin chain ignores X-Environment-Key entirely; with no valid JWT this is rejected.
        mockMvc.perform(get(ADMIN_ENDPOINT).header("X-Environment-Key", "bogus-key"))
                .andExpect(status().isForbidden());
    }

    @Test
    void validJwtCannotAuthenticateAgainstSdkChain() throws Exception {
        // A cryptographically valid Admin credential must NOT grant SDK access: the SDK
        // chain only honours X-Environment-Key, so the Bearer token is ignored and the
        // request is rejected with the API-key filter's 401.
        mockMvc.perform(get(SDK_ENDPOINT).header("Authorization", "Bearer " + validJwtForUnknownUser()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void bearerPlusBogusKeyOnSdkChainIsGovernedByKeyCheck() throws Exception {
        // Even with a Bearer token present, the SDK chain evaluates only the (invalid) key.
        mockMvc.perform(get(SDK_ENDPOINT)
                        .header("Authorization", "Bearer " + validJwtForUnknownUser())
                        .header("X-Environment-Key", "bogus-key"))
                .andExpect(status().isUnauthorized());
    }
}
