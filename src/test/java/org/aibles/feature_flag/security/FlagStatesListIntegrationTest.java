package org.aibles.feature_flag.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.aibles.feature_flag.domain.entity.User;
import org.aibles.feature_flag.dto.response.FlagStateResponse;
import org.aibles.feature_flag.exception.ResourceNotFoundException;
import org.aibles.feature_flag.exception.UnauthorizedException;
import org.aibles.feature_flag.service.FeatureFlagService;
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
 * S-2.6: {@code GET /flags/{flagId}/environments} lists every state of one flag. The project comes
 * from the flag, the read needs FLAG_READ on it (403), an unknown flag is 404, and a state row
 * whose environment belongs to another project (anomaly, seeded here) is never returned. Runs the
 * real PDP against the S-0.0 synthetic fixture.
 */
@SpringBootTest(
    properties = {
      "spring.main.allow-bean-definition-overriding=true",
      "spring.datasource.url=jdbc:h2:mem:flagstates-testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;"
          + "MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=KEY,VALUE;"
          + "CASE_INSENSITIVE_IDENTIFIERS=TRUE"
    })
@ActiveProfiles("test")
@Import(FixedClocksTestConfig.class)
class FlagStatesListIntegrationTest {

  @Autowired FeatureFlagService service;
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

  @Test
  @DisplayName("AC1: returns every state of the flag, one per environment of its project")
  void returnsAllStates() {
    as(FixtureIds.USER_VIEWER_X);
    List<FlagStateResponse> states = service.listStates(FixtureIds.FLAG_A1);

    assertThat(states)
        .extracting(FlagStateResponse::getEnvironmentId)
        .containsExactlyInAnyOrder(
            FixtureIds.ENV_A_DEV, FixtureIds.ENV_A_STG, FixtureIds.ENV_A_PROD);
    assertThat(states).allSatisfy(s -> assertThat(s.getFlagId()).isEqualTo(FixtureIds.FLAG_A1));
    assertThat(states).allSatisfy(s -> assertThat(s.getVersion()).isNotNull());
    assertThat(states)
        .filteredOn(s -> s.getEnvironmentId().equals(FixtureIds.ENV_A_PROD))
        .singleElement()
        .satisfies(s -> assertThat(s.getRolloutPercent()).isEqualTo(50));
  }

  @Test
  @DisplayName("AC2: an anomalous state row whose env is in another project is not returned")
  void crossProjectStateRowIsNotReturned() {
    jdbc.update(
        "insert into flag_environment_states (id, feature_flag_id, environment_id, enabled,"
            + " value, rollout_percent, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?)",
        FixtureIds.stateId(FixtureIds.FLAG_A1, FixtureIds.ENV_B_PROD),
        FixtureIds.FLAG_A1,
        FixtureIds.ENV_B_PROD,
        true,
        "true",
        100,
        SyntheticFixture.CLOCK.instant().atZone(java.time.ZoneOffset.UTC).toLocalDateTime(),
        SyntheticFixture.CLOCK.instant().atZone(java.time.ZoneOffset.UTC).toLocalDateTime());

    as(FixtureIds.USER_OWNER_X);
    assertThat(service.listStates(FixtureIds.FLAG_A1))
        .extracting(FlagStateResponse::getEnvironmentId)
        .doesNotContain(FixtureIds.ENV_B_PROD, FixtureIds.ENV_B_DEV)
        .hasSize(3);
  }

  @Test
  @DisplayName("AC3: no FLAG_READ on the flag's project -> 403 (Unauthorized)")
  void withoutFlagRead_is403() {
    for (UUID user :
        List.of(FixtureIds.USER_OUTSIDER_X, FixtureIds.USER_MEMBER_Y, FixtureIds.USER_NOACCESS)) {
      as(user);
      assertThatThrownBy(() -> service.listStates(FixtureIds.FLAG_A1))
          .as("user %s", user)
          .isInstanceOf(UnauthorizedException.class);
    }
  }

  @Test
  @DisplayName("AC4: unknown flag -> 404")
  void unknownFlag_is404() {
    as(FixtureIds.USER_OWNER_X);
    assertThatThrownBy(() -> service.listStates(UUID.randomUUID()))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  @DisplayName("Read is side-effect free: no audit row written (D-11)")
  void readWritesNoAudit() {
    as(FixtureIds.USER_OWNER_X);
    long before = jdbc.queryForObject("select count(*) from audit_log", Long.class);
    service.listStates(FixtureIds.FLAG_A1);
    assertThat(jdbc.queryForObject("select count(*) from audit_log", Long.class)).isEqualTo(before);
  }
}
