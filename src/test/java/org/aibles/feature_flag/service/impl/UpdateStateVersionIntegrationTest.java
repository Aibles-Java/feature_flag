package org.aibles.feature_flag.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.aibles.feature_flag.dto.request.UpdateFlagStateRequest;
import org.aibles.feature_flag.dto.response.FlagStateResponse;
import org.aibles.feature_flag.exception.InvalidRequestException;
import org.aibles.feature_flag.notification.event.FlagStateChangedEvent;
import org.aibles.feature_flag.repository.FlagEnvironmentStateRepository;
import org.aibles.feature_flag.service.FeatureFlagService;
import org.aibles.feature_flag.testsupport.FixtureIds;
import org.aibles.feature_flag.testsupport.SyntheticFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * S-2.2 (ADR-07, F18, D-05(2)): PUT state is optimistic-locked on {@code version}. Real service +
 * real Liquibase schema + the S-0.0 synthetic fixture.
 *
 * <p>Fixture: flag A2 in ENV_A_DEV, enabled=true, value="blue", version 0 at load.
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:stateversion-testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=KEY,VALUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
    })
@ActiveProfiles("test")
@RecordApplicationEvents
class UpdateStateVersionIntegrationTest {

  static final UUID FLAG = FixtureIds.FLAG_A2;
  static final UUID ENV = FixtureIds.ENV_A_DEV;

  @Autowired FeatureFlagService service;
  @Autowired JdbcTemplate jdbc;
  @Autowired ApplicationEvents events;

  @MockitoBean PermissionService permissionService;
  @Autowired FlagEnvironmentStateRepository stateRepository;
  private Object serviceTarget;

  private ListAppender<ILoggingEvent> logs;
  private Logger serviceLogger;

  @BeforeEach
  void load() {
    Object t = AopProxyUtils.getSingletonTarget(service);
    serviceTarget = t == null ? service : t;
    SyntheticFixture.load(jdbc);
    jdbc.update("delete from audit_log where org_id = ?", FixtureIds.ORG_X);
    setRequireVersion(false);
    serviceLogger = (Logger) LoggerFactory.getLogger(FeatureFlagServiceImpl.class);
    logs = new ListAppender<>();
    logs.start();
    serviceLogger.addAppender(logs);
  }

  @AfterEach
  void cleanup() {
    serviceLogger.detachAppender(logs);
    setRequireVersion(false);
    ReflectionTestUtils.setField(serviceTarget, "flagStateRepository", stateRepository);
  }

  private void setRequireVersion(boolean on) {
    ReflectionTestUtils.setField(serviceTarget, "requireVersion", on);
  }

  private long dbVersion() {
    return jdbc.queryForObject(
        "select version from flag_environment_states where feature_flag_id = ? and environment_id = ?",
        Long.class,
        FLAG,
        ENV);
  }

  private String dbValue() {
    return jdbc.queryForObject(
        "select value from flag_environment_states where feature_flag_id = ? and environment_id = ?",
        String.class,
        FLAG,
        ENV);
  }

  private long auditRows() {
    return jdbc.queryForObject(
        "select count(*) from audit_log where org_id = ?", Long.class, FixtureIds.ORG_X);
  }

  private UpdateFlagStateRequest req(String value, Long version) {
    UpdateFlagStateRequest r = new UpdateFlagStateRequest();
    r.setEnabled(true);
    r.setValue(value);
    r.setVersion(version);
    return r;
  }

  private void assertNothingWritten() {
    assertThat(dbValue()).isEqualTo("blue");
    assertThat(dbVersion()).isZero();
    assertThat(auditRows()).isZero();
    assertThat(events.stream(FlagStateChangedEvent.class)).isEmpty();
  }

  @Test
  @DisplayName("T-F18-1 / AC1: two concurrent PUTs with the same version -> one 200, one 409")
  void concurrentSameVersionOneWins() throws Exception {
    // Deterministic sync point (no sleep): both requests must have READ the row (version 0)
    // before either proceeds to write.
    CyclicBarrier bothRead = new CyclicBarrier(2);
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
                  if (method.getName().equals("findByFeatureFlagIdAndEnvironmentId")) {
                    bothRead.await(10, TimeUnit.SECONDS);
                  }
                  return result;
                });
    ReflectionTestUtils.setField(serviceTarget, "flagStateRepository", gated);

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Callable<FlagStateResponse> a = () -> service.updateState(FLAG, ENV, req("red", 0L));
      Callable<FlagStateResponse> b = () -> service.updateState(FLAG, ENV, req("green", 0L));
      Future<FlagStateResponse> fa = pool.submit(a);
      Future<FlagStateResponse> fb = pool.submit(b);

      int ok = 0;
      int conflict = 0;
      String winnerValue = null;
      for (Future<FlagStateResponse> f : List.of(fa, fb)) {
        try {
          FlagStateResponse r = f.get(30, TimeUnit.SECONDS);
          ok++;
          winnerValue = r.getValue();
          assertThat(r.getVersion()).isEqualTo(1L);
        } catch (java.util.concurrent.ExecutionException e) {
          assertThat(e.getCause()).isInstanceOf(ObjectOptimisticLockingFailureException.class);
          conflict++;
        }
      }
      assertThat(ok).isEqualTo(1);
      assertThat(conflict).isEqualTo(1);
      assertThat(dbValue()).isEqualTo(winnerValue);
      assertThat(dbVersion()).isEqualTo(1L);
      assertThat(auditRows()).isEqualTo(1L);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("AC1: a stale supplied version -> 409, no write, audit or event (both modes)")
  void staleVersionConflictsInBothModes() {
    for (boolean on : new boolean[] {false, true}) {
      setRequireVersion(on);
      assertThatThrownBy(() -> service.updateState(FLAG, ENV, req("red", 5L)))
          .isInstanceOf(ObjectOptimisticLockingFailureException.class);
      assertNothingWritten();
    }
  }

  @Test
  @DisplayName("a matching version succeeds and bumps the version (both modes)")
  void matchingVersionSucceeds() {
    FlagStateResponse r1 = service.updateState(FLAG, ENV, req("red", 0L));
    assertThat(r1.getVersion()).isEqualTo(1L);
    setRequireVersion(true);
    FlagStateResponse r2 = service.updateState(FLAG, ENV, req("green", 1L));
    assertThat(r2.getVersion()).isEqualTo(2L);
    assertThat(dbValue()).isEqualTo("green");
  }

  @Test
  @DisplayName("AC2 step 1 (switch OFF): missing version -> still 200 + WARN without value")
  void missingVersionAllowedWithWarning() {
    FlagStateResponse r = service.updateState(FLAG, ENV, req("secret-ish-value", null));

    assertThat(r.getValue()).isEqualTo("secret-ish-value");
    assertThat(dbValue()).isEqualTo("secret-ish-value");
    List<ILoggingEvent> warns = logs.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
    assertThat(warns).hasSize(1);
    assertThat(warns.get(0).getFormattedMessage())
        .contains("without version")
        .doesNotContain("secret-ish-value")
        .doesNotContain("blue");
  }

  @Test
  @DisplayName("AC3 step 2 (switch ON): missing version -> 400, 0 DB writes, 0 audit, 0 event")
  void missingVersionRejectedWhenRequired() {
    setRequireVersion(true);

    assertThatThrownBy(() -> service.updateState(FLAG, ENV, req("red", null)))
        .isInstanceOf(InvalidRequestException.class);

    assertNothingWritten();
  }
}
