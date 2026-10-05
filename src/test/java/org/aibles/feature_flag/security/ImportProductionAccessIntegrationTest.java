package org.aibles.feature_flag.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.aibles.feature_flag.domain.entity.User;
import org.aibles.feature_flag.domain.enums.ImportConflictStrategy;
import org.aibles.feature_flag.domain.enums.ImportOutcome;
import org.aibles.feature_flag.dto.request.ImportEnvironmentRequest;
import org.aibles.feature_flag.dto.response.EnvironmentSnapshotResponse;
import org.aibles.feature_flag.dto.response.ImportResultResponse;
import org.aibles.feature_flag.exception.UnauthorizedException;
import org.aibles.feature_flag.service.EnvironmentTransferService;
import org.aibles.feature_flag.testsupport.FixedClocksTestConfig;
import org.aibles.feature_flag.testsupport.FixtureIds;
import org.aibles.feature_flag.testsupport.SyntheticFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.ActiveProfiles;

/**
 * S-0.6 (F23, T-IMP-4, D-15): importing into a PRODUCTION environment is OWNER-only and, for a
 * window with {@code start != end}, only inside the change window. {@code start == end} means "no
 * time restriction" (D-15) but never lifts the OWNER-only rule. Runs the real PDP against the S-0.0
 * synthetic fixture; both clocks are pinned to 10:00 UTC by {@link FixedClocksTestConfig}, so the
 * fixture's prod window 09-17 is open, and the tests move the window instead of the clock.
 */
@SpringBootTest(
    properties = {
      "spring.main.allow-bean-definition-overriding=true",
      "spring.datasource.url=jdbc:h2:mem:import-prod-testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;"
          + "MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=KEY,VALUE;"
          + "CASE_INSENSITIVE_IDENTIFIERS=TRUE"
    })
@ActiveProfiles("test")
@Import(FixedClocksTestConfig.class)
class ImportProductionAccessIntegrationTest {

  @Autowired EnvironmentTransferService service;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void load() {
    SyntheticFixture.load(jdbc);
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  private void as(UUID userId) {
    UserPrincipal principal =
        UserPrincipal.from(
            User.builder().id(userId).email("tst@example.test").passwordHash("x").build());
    SecurityContextHolder.setContext(
        new SecurityContextImpl(new UsernamePasswordAuthenticationToken(principal, null)));
  }

  private void window(Integer start, Integer end) {
    jdbc.update(
        "update environments set change_window_start_hour = ?, change_window_end_hour = ?"
            + " where id = ?",
        start,
        end,
        FixtureIds.ENV_A_PROD);
  }

  /** Overwrites the fixture flag's prod state: enabled=true differs from the fixture default. */
  private ImportEnvironmentRequest request() {
    ImportEnvironmentRequest.FlagEntry entry = new ImportEnvironmentRequest.FlagEntry();
    entry.setKey("tst-flag-checkout");
    entry.setValueType(org.aibles.feature_flag.domain.enums.FlagValueType.BOOLEAN);
    entry.setEnabled(true);
    entry.setValue("true");
    entry.setRolloutPercent(50);
    ImportEnvironmentRequest.Snapshot snapshot = new ImportEnvironmentRequest.Snapshot();
    snapshot.setSchemaVersion(EnvironmentSnapshotResponse.SCHEMA_VERSION);
    snapshot.setFlags(List.of(entry));
    ImportEnvironmentRequest req = new ImportEnvironmentRequest();
    req.setConflictStrategy(ImportConflictStrategy.OVERWRITE);
    req.setSnapshot(snapshot);
    return req;
  }

  private long auditCount() {
    return jdbc.queryForObject("select count(*) from audit_log", Long.class);
  }

  private void assertDeniedAndNothingWritten(UUID user) {
    as(user);
    List<String> before = SyntheticFixture.snapshot(jdbc);
    long audits = auditCount();

    assertThatThrownBy(() -> service.importSnapshot(FixtureIds.ENV_A_PROD, request()))
        .isInstanceOf(UnauthorizedException.class);

    assertThat(SyntheticFixture.snapshot(jdbc)).isEqualTo(before);
    assertThat(auditCount()).isEqualTo(audits);
  }

  @Test
  @DisplayName("T-IMP-4: ADMIN (not OWNER) importing into PROD inside the window -> 403, no write")
  void admin_intoProd_insideWindow_isDenied() {
    assertDeniedAndNothingWritten(FixtureIds.USER_ADMIN_X);
  }

  @Test
  @DisplayName("T-IMP-4: OWNER outside the window (start != end) -> 403, no write")
  void owner_intoProd_outsideWindow_isDenied() {
    window(20, 23); // clock is 10:00 UTC
    assertDeniedAndNothingWritten(FixtureIds.USER_OWNER_X);
  }

  @Test
  @DisplayName("T-IMP-4: OWNER inside the window is allowed (control)")
  void owner_intoProd_insideWindow_isAllowed() {
    as(FixtureIds.USER_OWNER_X);
    ImportResultResponse result = service.importSnapshot(FixtureIds.ENV_A_PROD, request());
    assertThat(result.getItems().get(0).getOutcome()).isEqualTo(ImportOutcome.UPDATED);
  }

  @Test
  @DisplayName("D-15: window start == end means unlimited, so OWNER may import at any hour")
  void owner_intoProd_startEqualsEnd_isAllowedAtAnyHour() {
    window(20, 20); // would exclude 10:00 if it were read as a 0-width window
    as(FixtureIds.USER_OWNER_X);

    ImportResultResponse result = service.importSnapshot(FixtureIds.ENV_A_PROD, request());

    assertThat(result.getItems().get(0).getOutcome()).isEqualTo(ImportOutcome.UPDATED);
    assertThat(
            jdbc.queryForObject(
                "select rollout_percent from flag_environment_states where environment_id = ?"
                    + " and feature_flag_id = ?",
                Integer.class,
                FixtureIds.ENV_A_PROD,
                FixtureIds.FLAG_A1))
        .isEqualTo(50);
  }

  @Test
  @DisplayName("D-15: start == end does not lift the OWNER-only rule - ADMIN still 403")
  void admin_intoProd_startEqualsEnd_isStillDenied() {
    window(20, 20);
    assertDeniedAndNothingWritten(FixtureIds.USER_ADMIN_X);
  }
}
