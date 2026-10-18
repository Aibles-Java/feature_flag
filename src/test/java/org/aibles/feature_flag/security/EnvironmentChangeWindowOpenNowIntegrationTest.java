package org.aibles.feature_flag.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.aibles.feature_flag.config.AppConfig;
import org.aibles.feature_flag.domain.entity.User;
import org.aibles.feature_flag.dto.response.EnvironmentResponse;
import org.aibles.feature_flag.service.EnvironmentService;
import org.aibles.feature_flag.testsupport.FixtureIds;
import org.aibles.feature_flag.testsupport.SyntheticFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.ActiveProfiles;

/**
 * S-2.11 (D-09, D-15): {@code EnvironmentResponse.changeWindowZone/changeWindowOpenNow}. The two
 * clock beans DIFFER (general clock 10:00 UTC, change-window clock 21:00 UTC) so a result can only
 * come from the dedicated change-window clock.
 */
@SpringBootTest(
    properties = {
      "spring.main.allow-bean-definition-overriding=true",
      "spring.datasource.url=jdbc:h2:mem:envwindow-testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;"
          + "MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=KEY,VALUE;"
          + "CASE_INSENSITIVE_IDENTIFIERS=TRUE"
    })
@ActiveProfiles("test")
@Import(EnvironmentChangeWindowOpenNowIntegrationTest.DivergentClocks.class)
class EnvironmentChangeWindowOpenNowIntegrationTest {

  @TestConfiguration
  static class DivergentClocks {
    @Bean
    @Primary
    public Clock clock() {
      return Clock.fixed(Instant.parse("2026-01-15T10:00:00Z"), ZoneId.of("UTC"));
    }

    @Bean(AppConfig.CHANGE_WINDOW_CLOCK)
    public Clock changeWindowClock() {
      return Clock.fixed(Instant.parse("2026-01-15T21:00:00Z"), ZoneId.of("UTC"));
    }
  }

  @Autowired EnvironmentService service;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void load() {
    SyntheticFixture.load(jdbc);
    UserPrincipal p =
        UserPrincipal.from(
            User.builder()
                .id(FixtureIds.USER_OWNER_X)
                .email("tst@example.test")
                .passwordHash("x")
                .build());
    SecurityContextHolder.setContext(
        new SecurityContextImpl(new UsernamePasswordAuthenticationToken(p, null)));
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
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

  @Test
  @DisplayName("window [9,17) is closed at the change-window clock's 21:00 even though 10:00 is in")
  void usesTheDedicatedClockNotTheGeneralOne() {
    window(FixtureIds.ENV_A_PROD, 9, 17, null);
    EnvironmentResponse r = service.get(FixtureIds.ENV_A_PROD);
    assertThat(r.getChangeWindowOpenNow()).isFalse();
    assertThat(r.getChangeWindowZone()).isEqualTo("UTC");
  }

  @Test
  @DisplayName("midnight-wrap window [22,6) in the environment's own zone (UTC+7: 04:00 local)")
  void perEnvironmentZoneOverridesConfiguredZone() {
    window(FixtureIds.ENV_B_PROD, 22, 6, "Asia/Ho_Chi_Minh");
    EnvironmentResponse r = service.get(FixtureIds.ENV_B_PROD);
    assertThat(r.getChangeWindowZone()).isEqualTo("Asia/Ho_Chi_Minh");
    assertThat(r.getChangeWindowOpenNow()).isTrue();
    // same hours read in the configured zone (UTC 21:00) are closed
    window(FixtureIds.ENV_B_PROD, 22, 6, null);
    r = service.get(FixtureIds.ENV_B_PROD);
    assertThat(r.getChangeWindowZone()).isEqualTo("UTC");
    assertThat(r.getChangeWindowOpenNow()).isFalse();
  }

  @Test
  @DisplayName("invalid stored zone falls back to the configured zone, like PermissionService")
  void invalidZoneFallsBack() {
    window(FixtureIds.ENV_B_PROD, 20, 22, "Not/AZone");
    EnvironmentResponse r = service.get(FixtureIds.ENV_B_PROD);
    assertThat(r.getChangeWindowZone()).isEqualTo("UTC");
    assertThat(r.getChangeWindowOpenNow()).isTrue(); // 21:00 UTC in [20,22)
  }

  @Test
  @DisplayName("start == end (D-15) and no window both mean open")
  void startEqualsEndAndNoWindowAreOpen() {
    window(FixtureIds.ENV_A_PROD, 9, 9, null);
    assertThat(service.get(FixtureIds.ENV_A_PROD).getChangeWindowOpenNow()).isTrue();
    window(FixtureIds.ENV_A_PROD, null, null, null);
    assertThat(service.get(FixtureIds.ENV_A_PROD).getChangeWindowOpenNow()).isTrue();
  }

  @Test
  @DisplayName("list fills the same fields for every environment of the project")
  void listFillsFields() {
    window(FixtureIds.ENV_A_PROD, 9, 17, null);
    var page = service.listByProject(FixtureIds.PROJECT_A, PageRequest.of(0, 20));
    assertThat(page.getContent()).hasSize(3);
    assertThat(page.getContent())
        .allSatisfy(
            e -> {
              assertThat(e.getChangeWindowZone()).isEqualTo("UTC");
              assertThat(e.getChangeWindowOpenNow())
                  .isEqualTo(!e.getId().equals(FixtureIds.ENV_A_PROD));
            });
  }
}
