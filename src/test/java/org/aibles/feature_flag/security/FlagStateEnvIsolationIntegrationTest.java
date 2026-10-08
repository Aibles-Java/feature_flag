package org.aibles.feature_flag.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.aibles.feature_flag.domain.entity.User;
import org.aibles.feature_flag.dto.request.UpdateFlagStateRequest;
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
 * S-0.2 (F17, IDOR): {@code environmentId} must belong to the flag's project, and the check order
 * is project-scope permission (403) -> env membership (404) -> env-scoped permission (403). Runs
 * the real PDP against the S-0.0 synthetic fixture (fixed clock, 10:00 UTC).
 */
@SpringBootTest(
    properties = {
      "spring.main.allow-bean-definition-overriding=true",
      "spring.datasource.url=jdbc:h2:mem:idor-testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;"
          + "MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=KEY,VALUE;"
          + "CASE_INSENSITIVE_IDENTIFIERS=TRUE"
    })
@ActiveProfiles("test")
@Import(FixedClocksTestConfig.class)
class FlagStateEnvIsolationIntegrationTest {

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

  private static UpdateFlagStateRequest req(boolean enabled) {
    UpdateFlagStateRequest r = new UpdateFlagStateRequest();
    r.setEnabled(enabled);
    return r;
  }

  private long auditCount() {
    return jdbc.queryForObject("select count(*) from audit_log", Long.class);
  }

  @Test
  @DisplayName(
      "T-IDOR-1: PUT with a foreign env (PROD or not) -> identical 404, no write, no audit")
  void put_foreignEnv_isIdentical404_noWrite_noAudit() {
    as(FixtureIds.USER_OWNER_X);
    List<String> before = SyntheticFixture.snapshot(jdbc);
    long audits = auditCount();

    Throwable prod =
        thrown(() -> service.updateState(FixtureIds.FLAG_A1, FixtureIds.ENV_B_PROD, req(true)));
    Throwable dev =
        thrown(() -> service.updateState(FixtureIds.FLAG_A1, FixtureIds.ENV_B_DEV, req(true)));
    Throwable unknown =
        thrown(() -> service.updateState(FixtureIds.FLAG_A1, UUID.randomUUID(), req(true)));

    assertThat(prod).isInstanceOf(ResourceNotFoundException.class);
    assertThat(dev).isInstanceOf(ResourceNotFoundException.class);
    // same message regardless of the foreign env's type and of its existence, and no env id echoed
    assertThat(dev.getMessage()).isEqualTo(prod.getMessage()).isEqualTo(unknown.getMessage());
    assertThat(prod.getMessage()).doesNotContain(FixtureIds.ENV_B_PROD.toString());
    assertThat(SyntheticFixture.snapshot(jdbc)).isEqualTo(before);
    assertThat(auditCount()).isEqualTo(audits);
  }

  @Test
  @DisplayName("T-IDOR-2: GET with a foreign env -> 404")
  void get_foreignEnv_is404() {
    as(FixtureIds.USER_OWNER_X);
    assertThatThrownBy(() -> service.getState(FixtureIds.FLAG_A1, FixtureIds.ENV_B_PROD))
        .isInstanceOf(ResourceNotFoundException.class);
    assertThatThrownBy(() -> service.getState(FixtureIds.FLAG_A1, FixtureIds.ENV_B_DEV))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  @DisplayName(
      "T-ORD-1: caller without access to project A gets 403 for own-project and foreign env")
  void noAccess_get403_forBothOwnAndForeignEnv() {
    for (UUID user : List.of(FixtureIds.USER_OUTSIDER_X, FixtureIds.USER_MEMBER_Y)) {
      as(user);
      for (UUID env :
          List.of(
              FixtureIds.ENV_A_DEV,
              FixtureIds.ENV_A_PROD,
              FixtureIds.ENV_B_DEV,
              FixtureIds.ENV_B_PROD,
              FixtureIds.ENV_C_DEV)) {
        Throwable put = thrown(() -> service.updateState(FixtureIds.FLAG_A1, env, req(true)));
        Throwable get = thrown(() -> service.getState(FixtureIds.FLAG_A1, env));
        assertThat(put).as("PUT %s", env).isInstanceOf(UnauthorizedException.class);
        assertThat(get).as("GET %s", env).isInstanceOf(UnauthorizedException.class);
      }
    }
    // indistinguishable: same message for env in A and env elsewhere
    as(FixtureIds.USER_OUTSIDER_X);
    Throwable own =
        thrown(() -> service.updateState(FixtureIds.FLAG_A1, FixtureIds.ENV_A_DEV, req(true)));
    Throwable foreign =
        thrown(() -> service.updateState(FixtureIds.FLAG_A1, FixtureIds.ENV_B_PROD, req(true)));
    assertThat(own.getMessage()).isEqualTo(foreign.getMessage());
  }

  @Test
  @DisplayName("AC4: project-scope check precedes env membership; env check (PROD) follows it")
  void orderingIsScopeThenMembershipThenEnvAttributes() {
    // (ii) before (iii): no project access + foreign env -> 403 not 404 (covered above).
    // (iii) before (iv): ADMIN lacks PROD elevation; a foreign PROD env must still be 404,
    // not the 403 "requires elevated permission" that would leak the foreign env's type.
    as(FixtureIds.USER_ADMIN_X);
    assertThatThrownBy(
            () -> service.updateState(FixtureIds.FLAG_A1, FixtureIds.ENV_B_PROD, req(true)))
        .isInstanceOf(ResourceNotFoundException.class);
    // (iv) still applies to an env inside the project.
    assertThatThrownBy(
            () -> service.updateState(FixtureIds.FLAG_A1, FixtureIds.ENV_A_PROD, req(true)))
        .isInstanceOf(UnauthorizedException.class)
        .hasMessageContaining("elevated");
  }

  @Test
  @DisplayName("AC5: valid PUT/GET within the project still work; PROD keeps OWNER + change window")
  void regression_sameProject() {
    as(FixtureIds.USER_ADMIN_X);
    FlagStateResponse updated =
        service.updateState(FixtureIds.FLAG_A1, FixtureIds.ENV_A_STG, req(true));
    assertThat(updated.isEnabled()).isTrue();
    assertThat(service.getState(FixtureIds.FLAG_A1, FixtureIds.ENV_A_STG).isEnabled()).isTrue();

    as(FixtureIds.USER_OWNER_X); // A prod window 09-17 UTC, clock 10:00 -> allowed
    assertThat(
            service.updateState(FixtureIds.FLAG_A1, FixtureIds.ENV_A_PROD, req(true)).isEnabled())
        .isTrue();
  }

  private static Throwable thrown(Runnable r) {
    try {
      r.run();
    } catch (Throwable t) {
      return t;
    }
    throw new AssertionError("expected an exception");
  }
}
