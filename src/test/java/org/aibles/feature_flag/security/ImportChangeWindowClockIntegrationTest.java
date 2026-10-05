package org.aibles.feature_flag.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.aibles.feature_flag.config.AppConfig;
import org.aibles.feature_flag.domain.entity.User;
import org.aibles.feature_flag.domain.enums.FlagValueType;
import org.aibles.feature_flag.domain.enums.ImportConflictStrategy;
import org.aibles.feature_flag.domain.enums.ImportOutcome;
import org.aibles.feature_flag.dto.request.ImportEnvironmentRequest;
import org.aibles.feature_flag.dto.response.EnvironmentSnapshotResponse;
import org.aibles.feature_flag.exception.UnauthorizedException;
import org.aibles.feature_flag.service.EnvironmentTransferService;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.ActiveProfiles;

/**
 * S-0.6 review follow-up: the two clock beans DIFFER (general clock 10:00 UTC, change-window clock
 * 21:00 UTC), proving the import's PROD decision is driven by the dedicated change-window clock.
 */
@SpringBootTest(
    properties = {
      "spring.main.allow-bean-definition-overriding=true",
      "spring.datasource.url=jdbc:h2:mem:import-clock-testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;"
          + "MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=KEY,VALUE;"
          + "CASE_INSENSITIVE_IDENTIFIERS=TRUE"
    })
@ActiveProfiles("test")
@Import(ImportChangeWindowClockIntegrationTest.DivergentClocks.class)
class ImportChangeWindowClockIntegrationTest {

  @TestConfiguration
  static class DivergentClocks {
    @Bean
    @Primary
    public Clock clock() {
      return Clock.fixed(Instant.parse("2026-01-15T10:00:00Z"), ZoneOffset.UTC);
    }

    @Bean(AppConfig.CHANGE_WINDOW_CLOCK)
    public Clock changeWindowClock() {
      return Clock.fixed(Instant.parse("2026-01-15T21:00:00Z"), ZoneOffset.UTC);
    }
  }

  @Autowired EnvironmentTransferService service;
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

  private void window(int start, int end) {
    jdbc.update(
        "update environments set change_window_start_hour = ?, change_window_end_hour = ?"
            + " where id = ?",
        start,
        end,
        FixtureIds.ENV_A_PROD);
  }

  private ImportEnvironmentRequest request() {
    ImportEnvironmentRequest.FlagEntry e = new ImportEnvironmentRequest.FlagEntry();
    e.setKey("tst-flag-checkout");
    e.setValueType(FlagValueType.BOOLEAN);
    e.setEnabled(true);
    e.setValue("true");
    e.setRolloutPercent(50);
    ImportEnvironmentRequest.Snapshot s = new ImportEnvironmentRequest.Snapshot();
    s.setSchemaVersion(EnvironmentSnapshotResponse.SCHEMA_VERSION);
    s.setFlags(List.of(e));
    ImportEnvironmentRequest r = new ImportEnvironmentRequest();
    r.setConflictStrategy(ImportConflictStrategy.OVERWRITE);
    r.setSnapshot(s);
    return r;
  }

  @Test
  @DisplayName("window 20-23: change-window clock (21:00) is inside, general clock (10:00) is not -> OWNER allowed")
  void windowClockInside_generalClockOutside_allowed() {
    window(20, 23);
    assertThat(service.importSnapshot(FixtureIds.ENV_A_PROD, request()).getItems().get(0).getOutcome())
        .isEqualTo(ImportOutcome.UPDATED);
  }

  @Test
  @DisplayName("window 09-17: general clock (10:00) is inside, change-window clock (21:00) is not -> 403")
  void windowClockOutside_generalClockInside_denied() {
    window(9, 17);
    List<String> before = SyntheticFixture.snapshot(jdbc);
    assertThatThrownBy(() -> service.importSnapshot(FixtureIds.ENV_A_PROD, request()))
        .isInstanceOf(UnauthorizedException.class);
    assertThat(SyntheticFixture.snapshot(jdbc)).isEqualTo(before);
  }
}
