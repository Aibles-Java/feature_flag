package org.aibles.feature_flag.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.aibles.feature_flag.controller.admin.FeatureFlagController;
import org.aibles.feature_flag.domain.entity.FlagEnvironmentState;
import org.aibles.feature_flag.domain.entity.User;
import org.aibles.feature_flag.dto.request.UpdateFlagStateRequest;
import org.aibles.feature_flag.exception.GlobalExceptionHandler;
import org.aibles.feature_flag.notification.event.FlagStateChangedEvent;
import org.aibles.feature_flag.repository.FlagEnvironmentStateRepository;
import org.aibles.feature_flag.security.UserPrincipal;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * S-2.2 independent QA (HTTP level): real controller + advice + service + PDP + H2/Liquibase.
 * Covers response chaining, 409/400 mapping, error precedence and flush-failure side effects.
 */
@SpringBootTest(
    properties = {
      "spring.main.allow-bean-definition-overriding=true",
      "spring.datasource.url=jdbc:h2:mem:stateversion-http-testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=KEY,VALUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
    })
@ActiveProfiles("test")
@Import(FixedClocksTestConfig.class)
@RecordApplicationEvents
class UpdateStateVersionHttpQaTest {

  static final UUID FLAG = FixtureIds.FLAG_A2;
  static final UUID ENV = FixtureIds.ENV_A_DEV;
  static final String URL = "/api/v1/flags/" + FLAG + "/environments/" + ENV;

  @Autowired FeatureFlagService service;
  @Autowired JdbcTemplate jdbc;
  @Autowired ApplicationEvents events;
  @Autowired FlagEnvironmentStateRepository stateRepository;
  @MockitoBean EvaluationCacheService cache;

  private final ObjectMapper om = new ObjectMapper();
  private Object target;
  private MockMvc mvc;

  @BeforeEach
  void load() {
    Object t = AopProxyUtils.getSingletonTarget(service);
    target = t == null ? service : t;
    SyntheticFixture.load(jdbc);
    jdbc.update("delete from audit_log where org_id = ?", FixtureIds.ORG_X);
    ReflectionTestUtils.setField(target, "requireVersion", false);
    UserPrincipal p =
        UserPrincipal.from(
            User.builder()
                .id(FixtureIds.USER_OWNER_X)
                .email("tst@example.test")
                .passwordHash("x")
                .build());
    SecurityContextHolder.setContext(
        new SecurityContextImpl(new UsernamePasswordAuthenticationToken(p, null)));
    mvc =
        MockMvcBuilders.standaloneSetup(new FeatureFlagController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @AfterEach
  void cleanup() {
    SecurityContextHolder.clearContext();
    ReflectionTestUtils.setField(target, "requireVersion", false);
    ReflectionTestUtils.setField(target, "flagStateRepository", stateRepository);
  }

  private org.springframework.test.web.servlet.ResultActions putState(String body) throws Exception {
    return mvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content(body));
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

  private long audits() {
    return jdbc.queryForObject(
        "select count(*) from audit_log where org_id = ?", Long.class, FixtureIds.ORG_X);
  }

  private void assertUntouched() {
    assertThat(dbValue()).isEqualTo("blue");
    assertThat(dbVersion()).isZero();
    assertThat(audits()).isZero();
    assertThat(events.stream(FlagStateChangedEvent.class)).isEmpty();
    verify(cache, never()).evictAfterCommit(any());
  }

  @Test
  @DisplayName("HTTP: response carries the NEW version, so a client can chain two PUTs")
  void chainTwoPuts() throws Exception {
    putState("{\"enabled\":true,\"value\":\"red\",\"version\":0}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(1));
    putState("{\"enabled\":true,\"value\":\"green\",\"version\":1}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(2));
    // re-using the old version is now a conflict
    putState("{\"enabled\":true,\"value\":\"x\",\"version\":1}").andExpect(status().isConflict());
    assertThat(dbValue()).isEqualTo("green");
    assertThat(dbVersion()).isEqualTo(2L);
    assertThat(audits()).isEqualTo(2L);
  }

  @Test
  @DisplayName("HTTP: stale version -> 409 in both switch modes, nothing written")
  void staleIs409BothModes() throws Exception {
    for (boolean on : new boolean[] {false, true}) {
      ReflectionTestUtils.setField(target, "requireVersion", on);
      putState("{\"enabled\":false,\"value\":\"red\",\"version\":7}").andExpect(status().isConflict());
      assertUntouched();
    }
  }

  @Test
  @DisplayName("HTTP: missing version -> 200 when switch OFF, 400 (nothing written) when ON")
  void missingVersionByMode() throws Exception {
    ReflectionTestUtils.setField(target, "requireVersion", true);
    putState("{\"enabled\":true,\"value\":\"red\"}").andExpect(status().isBadRequest());
    assertUntouched();
    ReflectionTestUtils.setField(target, "requireVersion", false);
    putState("{\"enabled\":true,\"value\":\"red\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(1));
  }

  @Test
  @DisplayName("PRECEDENCE (documented): stale version beats invalid value / clearValue conflict")
  void staleVersionWinsOverValidationErrors() throws Exception {
    String tooLong = "x".repeat(8193);
    putState("{\"enabled\":true,\"value\":\"" + tooLong + "\",\"version\":9}")
        .andExpect(status().isConflict());
    putState("{\"enabled\":true,\"value\":\"a\",\"clearValue\":true,\"version\":9}")
        .andExpect(status().isConflict());
    assertUntouched();
  }

  @Test
  @DisplayName("PRECEDENCE: matching version + invalid value -> 400, version not bumped")
  void matchingVersionInvalidValueIs400() throws Exception {
    String tooLong = "x".repeat(8193);
    putState("{\"enabled\":true,\"value\":\"" + tooLong + "\",\"version\":0}")
        .andExpect(status().isBadRequest());
    putState("{\"enabled\":true,\"value\":\"a\",\"clearValue\":true,\"version\":0}")
        .andExpect(status().isBadRequest());
    assertUntouched();
  }

  @Test
  @DisplayName("PRECEDENCE: switch ON + missing version + invalid value -> 400 (version required)")
  void missingVersionOnInvalidValue() throws Exception {
    ReflectionTestUtils.setField(target, "requireVersion", true);
    putState("{\"enabled\":true,\"value\":\"" + "x".repeat(8193) + "\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value("version is required"));
    assertUntouched();
  }

  @Test
  @DisplayName("clearValue with matching version clears value and bumps version")
  void clearValueWithVersion() throws Exception {
    putState("{\"enabled\":true,\"clearValue\":true,\"version\":0}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(1));
    assertThat(dbValue()).isNull();
  }

  @Test
  @DisplayName("flush failure: audit, event and cache-evict are NOT executed; HTTP 409")
  void flushFailureSkipsSideEffects() throws Exception {
    FlagEnvironmentStateRepository failing =
        (FlagEnvironmentStateRepository)
            java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {FlagEnvironmentStateRepository.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("saveAndFlush")) {
                    throw new ObjectOptimisticLockingFailureException(
                        FlagEnvironmentState.class, "simulated");
                  }
                  try {
                    return method.invoke(stateRepository, args);
                  } catch (java.lang.reflect.InvocationTargetException e) {
                    throw e.getCause();
                  }
                });
    ReflectionTestUtils.setField(target, "flagStateRepository", failing);

    putState("{\"enabled\":true,\"value\":\"red\",\"version\":0}").andExpect(status().isConflict());
    assertThatThrownBy(() -> service.updateState(FLAG, ENV, request()))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    assertUntouched();
  }

  private UpdateFlagStateRequest request() {
    UpdateFlagStateRequest r = new UpdateFlagStateRequest();
    r.setEnabled(true);
    r.setValue("red");
    r.setVersion(0L);
    return r;
  }
}
