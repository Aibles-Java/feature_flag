package org.aibles.feature_flag.metrics;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

/**
 * Security regression for the blank-metrics-password case (issue #29): when no scraper password is
 * configured, the {@code metrics} account is created <em>disabled</em> so an empty password cannot
 * authenticate (otherwise {@code NoOpPasswordEncoder} matches empty-vs-empty and anyone could
 * scrape {@code /actuator/prometheus} as {@code metrics} with no password).
 *
 * <p>Overrides {@code app.metrics.password} to empty, which forks a dedicated context — so it is
 * pinned to its own H2 database name to avoid the shared-{@code testdb} Liquibase collision.
 */
@SpringBootTest(
    properties = {
      "app.metrics.password=",
      "spring.datasource.url=jdbc:h2:mem:blankpwdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
    })
@ActiveProfiles("test")
class PrometheusBlankPasswordIntegrationTest {

  @Autowired private WebApplicationContext webApplicationContext;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
  }

  @Test
  void emptyPasswordCannotAuthenticateWhenNoSecretConfigured() throws Exception {
    String creds =
        java.util.Base64.getEncoder()
            .encodeToString("metrics:".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    mockMvc
        .perform(get("/actuator/prometheus").header("Authorization", "Basic " + creds))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void prometheusStaysClosedWithNoCredentials() throws Exception {
    mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
  }
}
