package org.aibles.feature_flag.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import java.util.UUID;
import org.aibles.feature_flag.domain.entity.User;
import org.aibles.feature_flag.domain.enums.FlagValueType;
import org.aibles.feature_flag.domain.enums.ImportConflictStrategy;
import org.aibles.feature_flag.domain.enums.ImportOutcome;
import org.aibles.feature_flag.dto.request.ImportEnvironmentRequest;
import org.aibles.feature_flag.dto.response.EnvironmentSnapshotResponse;
import org.aibles.feature_flag.dto.response.ImportResultResponse;
import org.aibles.feature_flag.service.EnvironmentTransferService;
import org.aibles.feature_flag.testsupport.FixedClocksTestConfig;
import org.aibles.feature_flag.testsupport.FixtureIds;
import org.aibles.feature_flag.testsupport.SyntheticFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.ActiveProfiles;

/**
 * S-0.6 independent QA (T-IMP-1): value vs valueType on import, against the real service and an H2
 * database (not mocks), so "DB unchanged" and "no flag created" are asserted on rows.
 */
@SpringBootTest(
    properties = {
      "spring.main.allow-bean-definition-overriding=true",
      "spring.datasource.url=jdbc:h2:mem:import-valtype-testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;"
          + "MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=KEY,VALUE;"
          + "CASE_INSENSITIVE_IDENTIFIERS=TRUE"
    })
@ActiveProfiles("test")
@Import(FixedClocksTestConfig.class)
class ImportValueValidationIntegrationTest {

  private static final UUID ENV = FixtureIds.ENV_A_DEV;
  private static final String SECRET = "sup3r-s3cret-qa-value";

  @Autowired EnvironmentTransferService service;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void load() {
    SyntheticFixture.load(jdbc);
    jdbc.update("delete from feature_flags where key = 'qa-new-flag'");
    UserPrincipal principal =
        UserPrincipal.from(
            User.builder()
                .id(FixtureIds.USER_OWNER_X)
                .email("tst@example.test")
                .passwordHash("x")
                .build());
    SecurityContextHolder.setContext(
        new SecurityContextImpl(new UsernamePasswordAuthenticationToken(principal, null)));
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
    jdbc.update(
        "delete from flag_environment_states where feature_flag_id in"
            + " (select id from feature_flags where key = 'qa-new-flag')");
    jdbc.update("delete from feature_flags where key = 'qa-new-flag'");
  }

  private static ImportEnvironmentRequest.FlagEntry entry(
      String key, FlagValueType type, String value) {
    ImportEnvironmentRequest.FlagEntry e = new ImportEnvironmentRequest.FlagEntry();
    e.setKey(key);
    e.setName(key);
    e.setValueType(type);
    e.setEnabled(true);
    e.setValue(value);
    e.setRolloutPercent(70);
    return e;
  }

  private static ImportEnvironmentRequest req(
      ImportConflictStrategy strategy, boolean dryRun, ImportEnvironmentRequest.FlagEntry... es) {
    ImportEnvironmentRequest.Snapshot s = new ImportEnvironmentRequest.Snapshot();
    s.setSchemaVersion(EnvironmentSnapshotResponse.SCHEMA_VERSION);
    s.setFlags(List.of(es));
    ImportEnvironmentRequest r = new ImportEnvironmentRequest();
    r.setConflictStrategy(strategy);
    r.setDryRun(dryRun);
    r.setSnapshot(s);
    return r;
  }

  private ImportEnvironmentRequest mixed(ImportConflictStrategy strategy, boolean dryRun) {
    return req(
        strategy,
        dryRun,
        // (a) flag with a state in this env, INTEGER flag, bad value
        entry("tst-flag-page-size", FlagValueType.INTEGER, "abc" + SECRET),
        // (b) flag without a state in this env, JSON flag, bad value
        entry("tst-flag-limits", FlagValueType.JSON, "{bad" + SECRET),
        // (c) new flag, bad value
        entry("qa-new-flag", FlagValueType.BOOLEAN, "yes" + SECRET),
        // over the 8192 limit (STRING accepts anything else)
        entry("tst-flag-theme", FlagValueType.STRING, "x".repeat(8193)),
        // valid, state differs from the fixture (enabled/rollout) so OVERWRITE updates it
        entry("tst-flag-checkout", FlagValueType.BOOLEAN, "false"));
  }

  private void dropLimitsStateInEnv() {
    jdbc.update(
        "delete from flag_environment_states where feature_flag_id = ? and environment_id = ?",
        FixtureIds.FLAG_A4,
        ENV);
  }

  private List<String> rows() {
    return jdbc.queryForList(
        "select cast(feature_flag_id as varchar) || '|' || cast(environment_id as varchar) || '|'"
            + " || enabled || '|' || coalesce(value,'<null>') || '|' || coalesce(cast("
            + "rollout_percent as varchar),'<null>') from flag_environment_states order by 1",
        String.class);
  }

  private int newFlagCount() {
    return jdbc.queryForObject(
        "select count(*) from feature_flags where key = 'qa-new-flag'", Integer.class);
  }

  @ParameterizedTest
  @EnumSource(ImportConflictStrategy.class)
  @DisplayName("every strategy: a/b/c/over-limit SKIPPED, nothing written for them, no echo")
  void realRun_invalidEntriesSkipped_dbUntouchedForThem(ImportConflictStrategy strategy) {
    dropLimitsStateInEnv();
    List<String> before = rows();

    ImportResultResponse r = service.importSnapshot(ENV, mixed(strategy, false));

    assertThat(r.getItems())
        .extracting(
            ImportResultResponse.ItemResult::getFlagKey,
            ImportResultResponse.ItemResult::getOutcome)
        .startsWith(
            tuple("tst-flag-page-size", ImportOutcome.SKIPPED),
            tuple("tst-flag-limits", ImportOutcome.SKIPPED),
            tuple("qa-new-flag", ImportOutcome.SKIPPED),
            tuple("tst-flag-theme", ImportOutcome.SKIPPED));
    assertThat(r.getItems().subList(0, 4))
        .allSatisfy(
            i -> assertThat(i.getDetail()).startsWith("invalid value").doesNotContain(SECRET));
    assertThat(newFlagCount()).isZero();
    // only the valid entry may differ from the "before" rows
    List<String> after = rows();
    assertThat(after).hasSameSizeAs(before);
    List<String> changed = after.stream().filter(a -> !before.contains(a)).toList();
    if (strategy == ImportConflictStrategy.OVERWRITE) {
      assertThat(changed).hasSize(1);
      assertThat(changed.get(0))
          .contains(FixtureIds.FLAG_A1.toString())
          .containsIgnoringCase("|true|false|70");
    } else {
      assertThat(changed).isEmpty();
      assertThat(r.getItems().get(4).getOutcome()).isEqualTo(ImportOutcome.SKIPPED);
    }
  }

  @ParameterizedTest
  @EnumSource(ImportConflictStrategy.class)
  @DisplayName("dry-run item list (order, outcome, detail) equals the real run; dry run writes 0")
  void dryRun_equalsRealRun(ImportConflictStrategy strategy) {
    dropLimitsStateInEnv();
    List<String> before = rows();

    ImportResultResponse dry = service.importSnapshot(ENV, mixed(strategy, true));
    assertThat(rows()).isEqualTo(before);
    assertThat(newFlagCount()).isZero();
    ImportResultResponse real = service.importSnapshot(ENV, mixed(strategy, false));

    assertThat(dry.getItems()).usingRecursiveComparison().isEqualTo(real.getItems());
    assertThat(dry.getSummary()).usingRecursiveComparison().isEqualTo(real.getSummary());
  }

  @Test
  @DisplayName("valid entries are not collateral: new flag with valid value is created")
  void validNewFlag_stillCreated() {
    ImportResultResponse r =
        service.importSnapshot(
            ENV,
            req(
                ImportConflictStrategy.OVERWRITE,
                false,
                entry("qa-new-flag", FlagValueType.BOOLEAN, "true")));
    assertThat(r.getItems().get(0).getOutcome()).isEqualTo(ImportOutcome.CREATED);
    assertThat(newFlagCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("boundary: exactly 8192 chars accepted, 8193 skipped; null accepted for any type")
  void lengthBoundaryAndNull() {
    ImportResultResponse r =
        service.importSnapshot(
            ENV,
            req(
                ImportConflictStrategy.OVERWRITE,
                false,
                entry("tst-flag-theme", FlagValueType.STRING, "y".repeat(8192)),
                entry("tst-flag-page-size", FlagValueType.INTEGER, null),
                entry("tst-flag-limits", FlagValueType.JSON, null)));
    assertThat(r.getItems())
        .extracting(ImportResultResponse.ItemResult::getOutcome)
        .containsExactly(ImportOutcome.UPDATED, ImportOutcome.UPDATED, ImportOutcome.UPDATED);
    assertThat(
            jdbc.queryForObject(
                "select value from flag_environment_states where feature_flag_id = ? and"
                    + " environment_id = ?",
                String.class,
                FixtureIds.FLAG_A3,
                ENV))
        .isNull();
  }

  @Test
  @DisplayName("existing flag whose type differs from the entry: skipped, state untouched")
  void typeMismatchWithExistingFlag_skippedAndUntouched() {
    List<String> before = rows();
    ImportResultResponse r =
        service.importSnapshot(
            ENV,
            req(
                ImportConflictStrategy.OVERWRITE,
                false,
                // existing BOOLEAN; entry says INTEGER with a perfectly valid integer
                entry("tst-flag-checkout", FlagValueType.INTEGER, "5"),
                // existing BOOLEAN; entry says INTEGER with an invalid integer
                entry("tst-flag-theme", FlagValueType.INTEGER, "nope")));
    assertThat(r.getItems())
        .extracting(ImportResultResponse.ItemResult::getOutcome)
        .containsExactly(ImportOutcome.SKIPPED, ImportOutcome.SKIPPED);
    assertThat(r.getItems().get(0).getDetail()).startsWith("value type mismatch");
    assertThat(r.getItems().get(1).getDetail()).startsWith("invalid value");
    assertThat(rows()).isEqualTo(before);
  }
}
