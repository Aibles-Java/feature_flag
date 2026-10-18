package org.aibles.feature_flag.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.aibles.feature_flag.domain.entity.User;
import org.aibles.feature_flag.domain.enums.FlagValueType;
import org.aibles.feature_flag.domain.enums.ImportConflictStrategy;
import org.aibles.feature_flag.domain.enums.ImportOutcome;
import org.aibles.feature_flag.dto.request.ImportEnvironmentRequest;
import org.aibles.feature_flag.dto.request.UpdateFlagStateRequest;
import org.aibles.feature_flag.dto.response.EnvironmentSnapshotResponse;
import org.aibles.feature_flag.dto.response.ImportResultResponse;
import org.aibles.feature_flag.repository.FlagEnvironmentStateRepository;
import org.aibles.feature_flag.service.EnvironmentTransferService;
import org.aibles.feature_flag.service.EvaluationCacheService;
import org.aibles.feature_flag.service.FeatureFlagService;
import org.aibles.feature_flag.testsupport.FixedClocksTestConfig;
import org.aibles.feature_flag.testsupport.FixtureIds;
import org.aibles.feature_flag.testsupport.SyntheticFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * S-2.13 (T-IMP-2, ADR-07, D-17): an import reads and writes in ONE transaction; a concurrent PUT
 * that lands between the import's read and its write makes the whole import fail (409 at the
 * boundary) and roll back — no entry, no new flag, no IMPORT audit row, no cache eviction — while
 * the concurrent PUT's value survives. Real service, real schema (H2), real PermissionService.
 *
 * <p>The sync point is deterministic (no sleep): a repository proxy runs the competing PUT on
 * another thread, to completion, right after the import's read of one chosen state.
 */
@SpringBootTest(
    properties = {
      "spring.main.allow-bean-definition-overriding=true",
      "spring.datasource.url=jdbc:h2:mem:import-conflict-testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;"
          + "MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=KEY,VALUE;"
          + "CASE_INSENSITIVE_IDENTIFIERS=TRUE"
    })
@ActiveProfiles("test")
@Import(FixedClocksTestConfig.class)
class ImportConcurrentWriteIntegrationTest {

  private static final UUID ENV = FixtureIds.ENV_A_DEV;
  private static final UUID RACED_FLAG = FixtureIds.FLAG_A2; // tst-flag-theme, STRING, "blue"
  private static final UUID OTHER_FLAG = FixtureIds.FLAG_A1; // tst-flag-checkout, BOOLEAN

  @Autowired EnvironmentTransferService service;
  @Autowired FeatureFlagService flagService;
  @Autowired FlagEnvironmentStateRepository stateRepository;
  @Autowired JdbcTemplate jdbc;
  @MockitoSpyBean EvaluationCacheService cache;

  private Object serviceTarget;

  @BeforeEach
  void load() {
    Object t = AopProxyUtils.getSingletonTarget(service);
    serviceTarget = t == null ? service : t;
    SyntheticFixture.load(jdbc);
    jdbc.update("delete from audit_log where org_id = ?", FixtureIds.ORG_X);
    jdbc.update("delete from feature_flags where key = 'qa-new-flag'");
    SecurityContextHolder.setContext(ownerContext());
  }

  @AfterEach
  void clear() {
    ReflectionTestUtils.setField(serviceTarget, "flagStateRepository", stateRepository);
    SecurityContextHolder.clearContext();
    jdbc.update(
        "delete from flag_environment_states where feature_flag_id in"
            + " (select id from feature_flags where key = 'qa-new-flag')");
    jdbc.update("delete from feature_flags where key = 'qa-new-flag'");
  }

  private static SecurityContext ownerContext() {
    UserPrincipal principal =
        UserPrincipal.from(
            User.builder()
                .id(FixtureIds.USER_OWNER_X)
                .email("tst@example.test")
                .passwordHash("x")
                .build());
    return new SecurityContextImpl(new UsernamePasswordAuthenticationToken(principal, null));
  }

  /** Competing PUT on another thread, joined before returning (happens-before the import write). */
  private void concurrentPut(UUID flag, String value) throws Exception {
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      pool.submit(
              () -> {
                SecurityContextHolder.setContext(ownerContext());
                try {
                  UpdateFlagStateRequest r = new UpdateFlagStateRequest();
                  r.setEnabled(true);
                  r.setValue(value);
                  flagService.updateState(flag, ENV, r);
                } finally {
                  SecurityContextHolder.clearContext();
                }
                return null;
              })
          .get(30, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }
  }

  private void raceAfterReadOf(UUID flag, String concurrentValue) {
    FlagEnvironmentStateRepository gated =
        (FlagEnvironmentStateRepository)
            java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {FlagEnvironmentStateRepository.class},
                (proxy, method, args) -> {
                  Object result;
                  try {
                    result = method.invoke(stateRepository, args);
                  } catch (java.lang.reflect.InvocationTargetException e) {
                    throw e.getCause();
                  }
                  if (method.getName().equals("findByFeatureFlagIdAndEnvironmentId")
                      && flag.equals(args[0])) {
                    concurrentPut(flag, concurrentValue);
                  }
                  return result;
                });
    ReflectionTestUtils.setField(serviceTarget, "flagStateRepository", gated);
  }

  private static ImportEnvironmentRequest.FlagEntry entry(
      String key, FlagValueType type, boolean enabled, String value) {
    ImportEnvironmentRequest.FlagEntry e = new ImportEnvironmentRequest.FlagEntry();
    e.setKey(key);
    e.setName(key);
    e.setValueType(type);
    e.setEnabled(enabled);
    e.setValue(value);
    e.setRolloutPercent(70);
    return e;
  }

  /** New flag, then an update that flushes fine, then the raced update (order matters). */
  private static ImportEnvironmentRequest request(boolean dryRun) {
    ImportEnvironmentRequest.Snapshot s = new ImportEnvironmentRequest.Snapshot();
    s.setSchemaVersion(EnvironmentSnapshotResponse.SCHEMA_VERSION);
    s.setFlags(
        List.of(
            entry("qa-new-flag", FlagValueType.BOOLEAN, true, "true"),
            entry("tst-flag-checkout", FlagValueType.BOOLEAN, false, "false"),
            entry("tst-flag-theme", FlagValueType.STRING, true, "imported")));
    ImportEnvironmentRequest r = new ImportEnvironmentRequest();
    r.setConflictStrategy(ImportConflictStrategy.OVERWRITE);
    r.setDryRun(dryRun);
    r.setSnapshot(s);
    return r;
  }

  private String value(UUID flag) {
    return jdbc.queryForObject(
        "select value from flag_environment_states where feature_flag_id = ? and environment_id = ?",
        String.class,
        flag,
        ENV);
  }

  private long version(UUID flag) {
    return jdbc.queryForObject(
        "select version from flag_environment_states where feature_flag_id = ? and environment_id = ?",
        Long.class,
        flag,
        ENV);
  }

  private int count(String sql) {
    return jdbc.queryForObject(sql, Integer.class);
  }

  @Test
  @DisplayName(
      "T-IMP-2: PUT lands between import read and write -> conflict, whole import rolled back")
  void concurrentPutBetweenReadAndWrite_rollsBackWholeImport() {
    String checkoutBefore = value(OTHER_FLAG);
    long checkoutVersionBefore = version(OTHER_FLAG);
    boolean checkoutEnabledBefore =
        jdbc.queryForObject(
            "select enabled from flag_environment_states where feature_flag_id = ? and"
                + " environment_id = ?",
            Boolean.class,
            OTHER_FLAG,
            ENV);
    raceAfterReadOf(RACED_FLAG, "from-put");

    assertThatThrownBy(() -> service.importSnapshot(ENV, request(false)))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);

    // the concurrent PUT is the only writer that survived
    assertThat(value(RACED_FLAG)).isEqualTo("from-put");
    assertThat(version(RACED_FLAG)).isEqualTo(1L);
    // the entry that had already been flushed before the conflict is rolled back too
    assertThat(value(OTHER_FLAG)).isEqualTo(checkoutBefore);
    assertThat(version(OTHER_FLAG)).isEqualTo(checkoutVersionBefore);
    assertThat(
            jdbc.queryForObject(
                "select enabled from flag_environment_states where feature_flag_id = ? and"
                    + " environment_id = ?",
                Boolean.class,
                OTHER_FLAG,
                ENV))
        .isEqualTo(checkoutEnabledBefore);
    // no flag created, no state rows for it
    assertThat(count("select count(*) from feature_flags where key = 'qa-new-flag'")).isZero();
    // no IMPORT audit row (the PUT's own CHANGE_STATE row is the only audit row)
    assertThat(count("select count(*) from audit_log where action = 'IMPORT'")).isZero();
    assertThat(count("select count(*) from audit_log where action = 'CHANGE_STATE'")).isEqualTo(1);
    // the failed import queued no cache eviction: the import evicts every environment of the
    // project, so the siblings are never touched, and DEV was evicted once, by the PUT only
    verify(cache, never()).evictAfterCommit(FixtureIds.ENV_A_STG);
    verify(cache, never()).evictAfterCommit(FixtureIds.ENV_A_PROD);
    verify(cache, times(1)).evictAfterCommit(ENV);
  }

  @Test
  @DisplayName("no conflict: import writes normally and each written state's version increments")
  void noConflict_writesAndBumpsVersions() {
    long themeBefore = version(RACED_FLAG);
    long checkoutBefore = version(OTHER_FLAG);

    ImportResultResponse r = service.importSnapshot(ENV, request(false));

    assertThat(r.getSummary().getCreated()).isEqualTo(1);
    assertThat(r.getSummary().getUpdated()).isEqualTo(2);
    assertThat(value(RACED_FLAG)).isEqualTo("imported");
    assertThat(version(RACED_FLAG)).isEqualTo(themeBefore + 1);
    assertThat(version(OTHER_FLAG)).isEqualTo(checkoutBefore + 1);
    assertThat(count("select count(*) from feature_flags where key = 'qa-new-flag'")).isEqualTo(1);
    assertThat(count("select count(*) from audit_log where action = 'IMPORT'")).isEqualTo(1);
  }

  @Test
  @DisplayName("dry run: reports UPDATED/CREATED but writes nothing and never conflicts")
  void dryRun_writesNothing() {
    long before = version(RACED_FLAG);

    ImportResultResponse r = service.importSnapshot(ENV, request(true));

    assertThat(r.getItems())
        .extracting(ImportResultResponse.ItemResult::getOutcome)
        .containsExactly(ImportOutcome.CREATED, ImportOutcome.UPDATED, ImportOutcome.UPDATED);
    assertThat(version(RACED_FLAG)).isEqualTo(before);
    assertThat(value(RACED_FLAG)).isEqualTo("blue");
    assertThat(count("select count(*) from feature_flags where key = 'qa-new-flag'")).isZero();
    assertThat(count("select count(*) from audit_log where action = 'IMPORT'")).isZero();
  }
}
