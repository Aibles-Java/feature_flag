package org.aibles.feature_flag.flags;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import io.github.bucket4j.TimeMeter;
import java.time.Duration;
import java.util.UUID;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;

/**
 * S-2.10 / D-12 over the real security chain: 60 requests per minute per user on the matrix
 * endpoint, 429 before any matrix/DB work, per-user isolation, no impact on other endpoints. Time
 * is a manual meter (no sleeps).
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:matrix-ratelimit-testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;"
          + "MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=KEY,VALUE;"
          + "CASE_INSENSITIVE_IDENTIFIERS=TRUE",
      "app.rate-limit.enabled=true",
      "app.rate-limit.matrix.capacity=60",
      "app.rate-limit.matrix.refill-period=1m"
    })
@ActiveProfiles("test")
@Import(FlagMatrixRateLimitHttpIntegrationTest.TimeConfig.class)
class FlagMatrixRateLimitHttpIntegrationTest {

  private static final String URL = "/api/v1/flags/environment-states";
  private static final ManualTimeMeter TIME = new ManualTimeMeter();

  @TestConfiguration
  static class TimeConfig {
    @Bean
    @Primary
    TimeMeter manualRateLimitTimeMeter() {
      return TIME;
    }
  }

  @Autowired WebApplicationContext ctx;
  @Autowired JwtTokenProvider jwt;
  @Autowired JdbcTemplate jdbc;
  @MockitoSpyBean FlagMatrixService matrixService;
  MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc = webAppContextSetup(ctx).apply(springSecurity()).build();
    SyntheticFixture.load(jdbc);
    // fresh window for every test: buckets from earlier tests idle out (> 2x period)
    TIME.advance(Duration.ofMinutes(10));
    clearInvocations(matrixService);
  }

  private String bearer(UUID user) {
    String email = jdbc.queryForObject("select email from users where id = ?", String.class, user);
    return "Bearer "
        + jwt.generateToken(
            UserPrincipal.from(User.builder().id(user).email(email).passwordHash("x").build()));
  }

  private MvcResult call(UUID user) throws Exception {
    return mvc.perform(
            get(URL)
                .param("projectId", FixtureIds.PROJECT_A.toString())
                .header("Authorization", bearer(user)))
        .andReturn();
  }

  private int status(UUID user) throws Exception {
    return call(user).getResponse().getStatus();
  }

  @Test
  @DisplayName("60th request is 200, 61st is 429 ProblemDetail with Retry-After, no matrix work")
  void sixtyFirstIsRefusedBeforeAnyMatrixWork() throws Exception {
    for (int i = 1; i <= 60; i++) {
      assertThat(status(FixtureIds.USER_OWNER_X)).as("request %d", i).isEqualTo(200);
    }
    clearInvocations(matrixService);

    MvcResult r = call(FixtureIds.USER_OWNER_X);
    assertThat(r.getResponse().getStatus()).isEqualTo(429);
    assertThat(r.getResponse().getHeader("Retry-After")).isNotNull();
    assertThat(Long.parseLong(r.getResponse().getHeader("Retry-After"))).isBetween(1L, 60L);
    assertThat(r.getResponse().getContentAsString())
        .contains("\"status\":429")
        .contains("Too Many Requests")
        .doesNotContain(FixtureIds.USER_OWNER_X.toString());
    verifyNoInteractions(matrixService);
  }

  @Test
  @DisplayName("a new window allows requests again")
  void newWindowAllowsAgain() throws Exception {
    for (int i = 0; i < 60; i++) status(FixtureIds.USER_OWNER_X);
    assertThat(status(FixtureIds.USER_OWNER_X)).isEqualTo(429);
    TIME.advance(Duration.ofMinutes(1));
    assertThat(status(FixtureIds.USER_OWNER_X)).isEqualTo(200);
  }

  @Test
  @DisplayName("another user is not affected; limit is per user")
  void otherUserNotAffected() throws Exception {
    for (int i = 0; i < 61; i++) status(FixtureIds.USER_OWNER_X);
    assertThat(status(FixtureIds.USER_OWNER_X)).isEqualTo(429);
    assertThat(status(FixtureIds.USER_ADMIN_X)).isEqualTo(200);
  }

  @Test
  @DisplayName("other endpoints are not limited by the matrix bucket")
  void otherEndpointsUnaffected() throws Exception {
    for (int i = 0; i < 61; i++) status(FixtureIds.USER_OWNER_X);
    assertThat(status(FixtureIds.USER_OWNER_X)).isEqualTo(429);
    for (int i = 0; i < 5; i++) {
      var res =
          mvc.perform(
                  get("/api/v1/flags/" + FixtureIds.FLAG_A1)
                      .header("Authorization", bearer(FixtureIds.USER_OWNER_X)))
              .andReturn()
              .getResponse();
      assertThat(res.getStatus()).isEqualTo(200);
    }
  }

  @Test
  @DisplayName("unauthenticated request stays 401 and never reaches the limiter")
  void unauthenticatedIs401() throws Exception {
    for (int i = 0; i < 70; i++) {
      assertThat(
              mvc.perform(get(URL).param("projectId", FixtureIds.PROJECT_A.toString()))
                  .andReturn()
                  .getResponse()
                  .getStatus())
          .isEqualTo(401);
    }
  }
}
