package org.aibles.feature_flag.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * Verifies the acceptance criteria of issue #29 end-to-end through the real management
 * {@code SecurityFilterChain}: {@code /actuator/prometheus} is not reachable without the
 * scraper credential, is scrapable with it, and includes the custom {@code ff_*} meters.
 * Health stays public. Uses the shared {@code test} context (credential set in
 * {@code application-test.properties}) so no second context is forked.
 */
@SpringBootTest
@ActiveProfiles("test")
class PrometheusEndpointIntegrationTest {

    // Matches application-test.properties.
    private static final String SCRAPER_USER = "metrics";
    private static final String SCRAPER_PASS = "test-metrics-secret";

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    void prometheusRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void prometheusRejectsWrongCredentials() throws Exception {
        mockMvc.perform(get("/actuator/prometheus").with(httpBasic(SCRAPER_USER, "wrong-password")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void prometheusScrapesWithScraperCredential() throws Exception {
        mockMvc.perform(get("/actuator/prometheus").with(httpBasic(SCRAPER_USER, SCRAPER_PASS)))
                .andExpect(status().isOk())
                // Default JVM metrics are present...
                .andExpect(content().string(org.hamcrest.Matchers.containsString("jvm_")))
                // ...and our custom meters are registered (definitions emitted even at zero count).
                .andExpect(content().string(org.hamcrest.Matchers.containsString("ff_")));
    }

    @Test
    void healthStaysPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    /**
     * Minimal HTTP Basic post-processor. Kept local to avoid importing
     * {@code SecurityMockMvcRequestPostProcessors.httpBasic}, which resolves credentials against
     * the app's user store rather than sending a raw Authorization header.
     */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor httpBasic(
            String user, String pass) {
        String token = java.util.Base64.getEncoder()
                .encodeToString((user + ":" + pass).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return request -> {
            request.addHeader("Authorization", "Basic " + token);
            return request;
        };
    }
}
