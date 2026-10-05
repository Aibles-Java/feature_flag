package org.aibles.feature_flag.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.aibles.feature_flag.controller.admin.FeatureFlagController;
import org.aibles.feature_flag.domain.entity.User;
import org.aibles.feature_flag.dto.request.UpdateFlagStateRequest;
import org.aibles.feature_flag.exception.GlobalExceptionHandler;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * S-0.2 independent QA: tries to break the {@code checkScope} deviation (VIEWER, grant-only user,
 * custom roles, ADMIN on PROD, OWNER outside the change window) and asserts the HTTP status mapping
 * through the real controller + advice + service + PDP.
 */
@SpringBootTest(
    properties = {
      "spring.main.allow-bean-definition-overriding=true",
      "spring.datasource.url=jdbc:h2:mem:idor-adv-testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;"
          + "MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=KEY,VALUE;"
          + "CASE_INSENSITIVE_IDENTIFIERS=TRUE"
    })
@ActiveProfiles("test")
@Import(FixedClocksTestConfig.class)
class FlagStateEnvIsolationAdversarialTest {

  @Autowired FeatureFlagService service;
  @Autowired JdbcTemplate jdbc;

  private static final UUID ROLE_ID = UUID.fromString("00000000-0000-4000-8000-999900000001");

  @BeforeEach
  void load() {
    cleanRole();
    SyntheticFixture.load(jdbc);
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
    cleanRole();
  }

  private void cleanRole() {
    jdbc.update("delete from permission_grant where custom_role_id = ?", ROLE_ID);
    jdbc.update("delete from custom_role_action where custom_role_id = ?", ROLE_ID);
    jdbc.update("delete from custom_role where id = ?", ROLE_ID);
  }

  private void as(UUID userId) {
    UserPrincipal principal =
        UserPrincipal.from(
            User.builder().id(userId).email("tst@example.test").passwordHash("x").build());
    SecurityContextHolder.setContext(
        new SecurityContextImpl(new UsernamePasswordAuthenticationToken(principal, null)));
  }

  private static UpdateFlagStateRequest req() {
    UpdateFlagStateRequest r = new UpdateFlagStateRequest();
    r.setEnabled(true);
    return r;
  }

  private void customRoleGrant(UUID user, String... actions) {
    jdbc.update(
        "insert into custom_role (id, organization_id, name, created_at) values (?,?,?,?)",
        ROLE_ID,
        FixtureIds.ORG_X,
        "qa-role",
        SyntheticFixture.AT);
    for (String a : actions) {
      jdbc.update(
          "insert into custom_role_action (custom_role_id, action) values (?,?)", ROLE_ID, a);
    }
    jdbc.update(
        "insert into permission_grant (id, user_id, scope_type, scope_id, custom_role_id,"
            + " created_at) values (?,?,?,?,?,?)",
        UUID.randomUUID(),
        user,
        "PROJECT",
        FixtureIds.PROJECT_A,
        ROLE_ID,
        SyntheticFixture.AT);
  }

  private long audits() {
    return jdbc.queryForObject("select count(*) from audit_log", Long.class);
  }

  @Test
  @DisplayName("VIEWER can read own-project state, 404 on foreign env, but never write (403)")
  void viewer() {
    as(FixtureIds.USER_VIEWER_X);
    List<String> snap = SyntheticFixture.snapshot(jdbc);
    assertThat(service.getState(FixtureIds.FLAG_A1, FixtureIds.ENV_A_STG)).isNotNull();
    assertThatThrownBy(() -> service.getState(FixtureIds.FLAG_A1, FixtureIds.ENV_B_PROD))
        .isInstanceOf(ResourceNotFoundException.class);
    for (UUID env :
        List.of(
            FixtureIds.ENV_A_DEV,
            FixtureIds.ENV_A_STG,
            FixtureIds.ENV_A_PROD,
            FixtureIds.ENV_B_DEV,
            FixtureIds.ENV_B_PROD)) {
      assertThatThrownBy(() -> service.updateState(FixtureIds.FLAG_A1, env, req()))
          .as("viewer PUT %s", env)
          .isInstanceOf(UnauthorizedException.class);
    }
    assertThat(SyntheticFixture.snapshot(jdbc)).isEqualTo(snap);
  }

  @Test
  @DisplayName("GET foreign env message equals unknown-env message (env membership asserted)")
  void getForeignEnvIndistinguishableFromUnknown() {
    // drifted data: a state row linking flag A1 to project B's env must still not be served
    jdbc.update(
        "insert into flag_environment_states (id, feature_flag_id, environment_id, enabled,"
            + " value, rollout_percent, created_at, updated_at) values (?,?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        FixtureIds.FLAG_A1,
        FixtureIds.ENV_B_PROD,
        true,
        null,
        100,
        SyntheticFixture.AT,
        SyntheticFixture.AT);
    as(FixtureIds.USER_OWNER_X);
    Throwable foreign = null;
    Throwable unknown = null;
    try {
      service.getState(FixtureIds.FLAG_A1, FixtureIds.ENV_B_PROD);
    } catch (Throwable t) {
      foreign = t;
    }
    try {
      service.getState(FixtureIds.FLAG_A1, UUID.randomUUID());
    } catch (Throwable t) {
      unknown = t;
    }
    assertThat(foreign).isInstanceOf(ResourceNotFoundException.class);
    assertThat(foreign.getMessage()).isEqualTo(unknown.getMessage());
    assertThat(foreign.getMessage()).contains("Environment not found");
  }

  @Test
  @DisplayName("grant-only ADMIN on A (org MEMBER): STG ok, PROD 403 elevated, foreign 404")
  void grantOnlyAdmin() {
    as(FixtureIds.USER_GRANT_A);
    assertThat(service.updateState(FixtureIds.FLAG_A1, FixtureIds.ENV_A_STG, req()).isEnabled())
        .isTrue();
    assertThatThrownBy(() -> service.updateState(FixtureIds.FLAG_A1, FixtureIds.ENV_A_PROD, req()))
        .isInstanceOf(UnauthorizedException.class)
        .hasMessageContaining("elevated");
    assertThatThrownBy(() -> service.updateState(FixtureIds.FLAG_A1, FixtureIds.ENV_B_PROD, req()))
        .isInstanceOf(ResourceNotFoundException.class);
    // grant on A gives nothing on project B's flag
    assertThatThrownBy(() -> service.updateState(FixtureIds.FLAG_B1, FixtureIds.ENV_B_STG, req()))
        .isInstanceOf(UnauthorizedException.class);
    assertThatThrownBy(() -> service.getState(FixtureIds.FLAG_B1, FixtureIds.ENV_A_STG))
        .isInstanceOf(UnauthorizedException.class);
  }

  @Test
  @DisplayName("custom role FLAG_STATE_UPDATE only: non-PROD ok, PROD 403, foreign 404")
  void customRoleBase() {
    customRoleGrant(FixtureIds.USER_NOACCESS, "FLAG_STATE_UPDATE", "FLAG_READ");
    as(FixtureIds.USER_NOACCESS);
    assertThat(service.updateState(FixtureIds.FLAG_A1, FixtureIds.ENV_A_DEV, req()).isEnabled())
        .isTrue();
    assertThatThrownBy(() -> service.updateState(FixtureIds.FLAG_A1, FixtureIds.ENV_A_PROD, req()))
        .isInstanceOf(UnauthorizedException.class)
        .hasMessageContaining("elevated");
    assertThatThrownBy(() -> service.updateState(FixtureIds.FLAG_A1, FixtureIds.ENV_B_DEV, req()))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  @DisplayName("custom role with only the PROD action (no base FLAG_STATE_UPDATE) is denied")
  void customRoleProdOnlyDenied() {
    customRoleGrant(FixtureIds.USER_NOACCESS, "FLAG_STATE_UPDATE_PRODUCTION", "FLAG_READ");
    as(FixtureIds.USER_NOACCESS);
    assertThatThrownBy(() -> service.updateState(FixtureIds.FLAG_A1, FixtureIds.ENV_A_PROD, req()))
        .isInstanceOf(UnauthorizedException.class);
    assertThatThrownBy(() -> service.updateState(FixtureIds.FLAG_A1, FixtureIds.ENV_B_PROD, req()))
        .isInstanceOf(UnauthorizedException.class);
  }

  @Test
  @DisplayName("custom role with read only: PUT 403 everywhere, GET own ok, GET foreign 404")
  void customRoleReadOnly() {
    customRoleGrant(FixtureIds.USER_NOACCESS, "FLAG_READ");
    as(FixtureIds.USER_NOACCESS);
    assertThatThrownBy(() -> service.updateState(FixtureIds.FLAG_A1, FixtureIds.ENV_A_DEV, req()))
        .isInstanceOf(UnauthorizedException.class);
    assertThat(service.getState(FixtureIds.FLAG_A1, FixtureIds.ENV_A_DEV)).isNotNull();
    assertThatThrownBy(() -> service.getState(FixtureIds.FLAG_A1, FixtureIds.ENV_B_DEV))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  @DisplayName("ADMIN on PROD in own project is 403 (no OWNER elevation), unchanged")
  void adminOnProd() {
    as(FixtureIds.USER_ADMIN_X);
    assertThatThrownBy(() -> service.updateState(FixtureIds.FLAG_A1, FixtureIds.ENV_A_PROD, req()))
        .isInstanceOf(UnauthorizedException.class)
        .hasMessageContaining("elevated");
  }

  @Test
  @DisplayName("OWNER outside the change window is 403 on own PROD; foreign env still 404")
  void ownerOutsideWindow() {
    jdbc.update(
        "update environments set change_window_start_hour = 1, change_window_end_hour = 2"
            + " where id = ?",
        FixtureIds.ENV_A_PROD);
    as(FixtureIds.USER_OWNER_X); // clock is 10:00 UTC
    long audits = audits();
    assertThatThrownBy(() -> service.updateState(FixtureIds.FLAG_A1, FixtureIds.ENV_A_PROD, req()))
        .isInstanceOf(UnauthorizedException.class)
        .hasMessageContaining("change window");
    assertThatThrownBy(() -> service.updateState(FixtureIds.FLAG_A1, FixtureIds.ENV_B_PROD, req()))
        .isInstanceOf(ResourceNotFoundException.class);
    assertThat(audits()).isEqualTo(audits);
    // a closed PROD window must not block a non-PROD env in the same project
    assertThat(service.updateState(FixtureIds.FLAG_A1, FixtureIds.ENV_A_STG, req()).isEnabled())
        .isTrue();
  }

  @Test
  @DisplayName(
      "HTTP mapping via real controller+advice: foreign env 404 (identical body), no-perm 403")
  void httpStatuses() throws Exception {
    MockMvc mvc =
        MockMvcBuilders.standaloneSetup(new FeatureFlagController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    String base = "/api/v1/flags/" + FixtureIds.FLAG_A1 + "/environments/";
    String body = "{\"enabled\":true}";

    as(FixtureIds.USER_OWNER_X);
    String prod =
        mvc.perform(
                put(base + FixtureIds.ENV_B_PROD)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isNotFound())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String dev =
        mvc.perform(
                put(base + FixtureIds.ENV_B_DEV)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isNotFound())
            .andReturn()
            .getResponse()
            .getContentAsString();
    mvc.perform(get(base + FixtureIds.ENV_B_PROD)).andExpect(status().isNotFound());
    // bodies identical apart from the per-request correlation id and the env path
    assertThat(strip(prod))
        .isEqualTo(
            strip(dev).replace(FixtureIds.ENV_B_DEV.toString(), FixtureIds.ENV_B_PROD.toString()));
    mvc.perform(
            put(base + FixtureIds.ENV_A_PROD).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(true));

    as(FixtureIds.USER_OUTSIDER_X);
    mvc.perform(
            put(base + FixtureIds.ENV_B_PROD).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isForbidden());
    mvc.perform(
            put(base + FixtureIds.ENV_A_DEV).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isForbidden());
    mvc.perform(get(base + FixtureIds.ENV_B_DEV)).andExpect(status().isForbidden());
    mvc.perform(get(base + FixtureIds.ENV_A_DEV)).andExpect(status().isForbidden());
  }

  private static String strip(String json) {
    return json.replaceAll("\"requestId\":\"[^\"]*\"", "")
        .replaceAll("\"timestamp\":\"[^\"]*\"", "");
  }
}
