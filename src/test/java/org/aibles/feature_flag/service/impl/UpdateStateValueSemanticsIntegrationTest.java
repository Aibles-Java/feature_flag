package org.aibles.feature_flag.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.aibles.feature_flag.dto.request.UpdateFlagStateRequest;
import org.aibles.feature_flag.dto.response.FlagStateResponse;
import org.aibles.feature_flag.exception.InvalidRequestException;
import org.aibles.feature_flag.notification.event.FlagStateChangedEvent;
import org.aibles.feature_flag.service.FeatureFlagService;
import org.aibles.feature_flag.testsupport.FixtureIds;
import org.aibles.feature_flag.testsupport.SyntheticFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * S-0.3 (ADR-05, F7): PUT state keeps {@code value} when absent/null; {@code clearValue:true} is
 * the only way to clear it. Real service + real Liquibase schema + the S-0.0 synthetic fixture.
 *
 * <p>Fixture: flag A2 (STRING) in ENV_A_DEV has enabled=true, value="blue", rolloutPercent=100.
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:clearvalue-testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=KEY,VALUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
    })
@ActiveProfiles("test")
@RecordApplicationEvents
class UpdateStateValueSemanticsIntegrationTest {

  static final UUID FLAG = FixtureIds.FLAG_A2;
  static final UUID ENV = FixtureIds.ENV_A_DEV;

  @Autowired FeatureFlagService service;
  @Autowired JdbcTemplate jdbc;
  @Autowired ApplicationEvents events;

  // Authorization is not under test here; the mock PDP allows everything.
  @MockitoBean PermissionService permissionService;

  @BeforeEach
  void load() {
    SyntheticFixture.load(jdbc);
    jdbc.update("delete from audit_log where org_id = ?", FixtureIds.ORG_X);
  }

  private String dbValue() {
    return jdbc.queryForObject(
        "select value from flag_environment_states where feature_flag_id = ? and environment_id = ?",
        String.class,
        FLAG,
        ENV);
  }

  private Integer dbRollout() {
    return jdbc.queryForObject(
        "select rollout_percent from flag_environment_states"
            + " where feature_flag_id = ? and environment_id = ?",
        Integer.class,
        FLAG,
        ENV);
  }

  private long auditRows() {
    return jdbc.queryForObject(
        "select count(*) from audit_log where org_id = ?", Long.class, FixtureIds.ORG_X);
  }

  private String auditAfter() {
    return jdbc.queryForObject(
        "select cast(after_state as varchar) from audit_log where org_id = ?"
            + " order by created_at desc limit 1",
        String.class,
        FixtureIds.ORG_X);
  }

  private UpdateFlagStateRequest req(boolean enabled, String value, Boolean clear) {
    UpdateFlagStateRequest r = new UpdateFlagStateRequest();
    r.setEnabled(enabled);
    r.setValue(value);
    r.setClearValue(clear);
    return r;
  }

  @Test
  @DisplayName("T-F7-1 / AC1: PUT without value keeps value in DB, response, audit after, webhook")
  void absentValueIsKept() {
    FlagStateResponse resp = service.updateState(FLAG, ENV, req(false, null, null));

    assertThat(resp.isEnabled()).isFalse();
    assertThat(resp.getValue()).isEqualTo("blue");
    assertThat(dbValue()).isEqualTo("blue");
    assertThat(auditAfter()).contains("blue");
    List<FlagStateChangedEvent> sent = events.stream(FlagStateChangedEvent.class).toList();
    assertThat(sent).hasSize(1);
    assertThat(sent.get(0).newValue()).isEqualTo("blue");
    assertThat(sent.get(0).previousValue()).isEqualTo("blue");
  }

  @Test
  @DisplayName("AC1: clearValue explicitly false behaves like absent (keeps)")
  void clearValueFalseKeeps() {
    service.updateState(FLAG, ENV, req(true, null, false));
    assertThat(dbValue()).isEqualTo("blue");
  }

  @Test
  @DisplayName("a non-null value still overwrites")
  void nonNullValueOverwrites() {
    service.updateState(FLAG, ENV, req(true, "green", null));
    assertThat(dbValue()).isEqualTo("green");
    assertThat(auditAfter()).contains("green");
  }

  @Test
  @DisplayName("T-F7-2 / AC2: clearValue:true sets value to null in DB, audit and webhook")
  void clearValueClears() {
    FlagStateResponse resp = service.updateState(FLAG, ENV, req(true, null, true));

    assertThat(resp.getValue()).isNull();
    assertThat(dbValue()).isNull();
    assertThat(auditAfter()).doesNotContain("blue");
    FlagStateChangedEvent e = events.stream(FlagStateChangedEvent.class).findFirst().orElseThrow();
    assertThat(e.previousValue()).isEqualTo("blue");
    assertThat(e.newValue()).isNull();
  }

  @Test
  @DisplayName("T-F7-2 / AC2: clearValue:true with value -> 400 and nothing written")
  void clearValueWithValueRejected() {
    assertThatThrownBy(() -> service.updateState(FLAG, ENV, req(false, "y", true)))
        .isInstanceOf(InvalidRequestException.class);

    assertThat(dbValue()).isEqualTo("blue");
    assertThat(auditRows()).isZero();
    assertThat(events.stream(FlagStateChangedEvent.class)).isEmpty();
  }

  @Test
  @DisplayName("AC3: rolloutPercent null keeps the stored percent")
  void nullRolloutKept() {
    service.updateState(FLAG, ENV, req(true, null, null));
    assertThat(dbRollout()).isEqualTo(100);
  }

  @Test
  @DisplayName("edge: empty string is a real value (overwrites), unlike null (keeps)")
  void emptyStringOverwrites() {
    service.updateState(FLAG, ENV, req(true, "", null));
    assertThat(dbValue()).isEmpty();
  }

  @Test
  @DisplayName("edge: clearValue:true with empty-string value -> 400, nothing written")
  void clearValueWithEmptyStringRejected() {
    assertThatThrownBy(() -> service.updateState(FLAG, ENV, req(false, "", true)))
        .isInstanceOf(InvalidRequestException.class);
    assertThat(dbValue()).isEqualTo("blue");
    assertThat(auditRows()).isZero();
    assertThat(events.stream(FlagStateChangedEvent.class)).isEmpty();
  }

  @Test
  @DisplayName("clearValue:true does not touch rolloutPercent")
  void clearValueKeepsRollout() {
    service.updateState(FLAG, ENV, req(true, null, true));
    assertThat(dbRollout()).isEqualTo(100);
  }
}
