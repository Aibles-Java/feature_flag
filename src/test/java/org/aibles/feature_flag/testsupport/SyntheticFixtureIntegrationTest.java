package org.aibles.feature_flag.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.aibles.feature_flag.domain.enums.EnvType;
import org.aibles.feature_flag.domain.enums.FlagValueType;
import org.aibles.feature_flag.domain.enums.MemberRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** S-0.0 AC1 + AC2: the synthetic multi-org/project fixture, against the real Liquibase schema. */
@SpringBootTest(
    properties = {
      // NON_KEYWORDS=KEY,VALUE: feature_flags.key is reserved in H2 2.x.
      "spring.datasource.url=jdbc:h2:mem:fixture-testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=KEY,VALUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
    })
@ActiveProfiles("test")
class SyntheticFixtureIntegrationTest {

  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void load() {
    SyntheticFixture.load(jdbc);
  }

  private long count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Long.class, args);
  }

  @Test
  @DisplayName("AC1: >= 2 orgs, each with >= 2 projects, each project >= 2 envs incl. 1 PRODUCTION")
  void hierarchyShape() {
    // Org Y has one project in the test-strategy §7 table; AC1 demands >= 2 per org.
    List<Map<String, Object>> orgs = jdbc.queryForList("select id from organizations");
    assertThat(orgs).hasSizeGreaterThanOrEqualTo(2);
    for (UUID org : List.of(FixtureIds.ORG_X, FixtureIds.ORG_Y)) {
      assertThat(count("select count(*) from projects where organization_id = ?", org))
          .isGreaterThanOrEqualTo(2);
    }
    for (UUID project : FixtureIds.ALL_PROJECTS) {
      assertThat(count("select count(*) from environments where project_id = ?", project))
          .isGreaterThanOrEqualTo(2);
      assertThat(
              count(
                  "select count(*) from environments where project_id = ? and type = ?",
                  project,
                  EnvType.PRODUCTION.name()))
          .isEqualTo(1);
    }
  }

  @Test
  @DisplayName("AC1: >= 3 flags covering all 4 valueTypes; every flag has a state per project env")
  void flagsAndStates() {
    assertThat(count("select count(*) from feature_flags")).isGreaterThanOrEqualTo(3);
    List<String> types =
        jdbc.queryForList("select distinct value_type from feature_flags", String.class);
    assertThat(types)
        .containsExactlyInAnyOrder(
            java.util.Arrays.stream(FlagValueType.values()).map(Enum::name).toArray(String[]::new));
    // flag x env matrix is dense within each project and never crosses projects
    assertThat(
            count(
                "select count(*) from flag_environment_states s"
                    + " join feature_flags f on f.id = s.feature_flag_id"
                    + " join environments e on e.id = s.environment_id"
                    + " where f.project_id <> e.project_id"))
        .isZero();
    assertThat(count("select count(*) from flag_environment_states"))
        .isEqualTo(
            count(
                "select count(*) from feature_flags f"
                    + " join environments e on e.project_id = f.project_id"));
  }

  @Test
  @DisplayName("AC1: users with OWNER / ADMIN / VIEWER / grant-only / no-access, fixed ids")
  void usersAndRoles() {
    assertThat(roleOf(FixtureIds.USER_OWNER_X, FixtureIds.ORG_X))
        .isEqualTo(MemberRole.OWNER.name());
    assertThat(roleOf(FixtureIds.USER_ADMIN_X, FixtureIds.ORG_X))
        .isEqualTo(MemberRole.ADMIN.name());
    assertThat(roleOf(FixtureIds.USER_VIEWER_X, FixtureIds.ORG_X))
        .isEqualTo(MemberRole.VIEWER.name());
    assertThat(roleOf(FixtureIds.USER_OUTSIDER_X, FixtureIds.ORG_X))
        .isEqualTo(MemberRole.MEMBER.name());
    assertThat(roleOf(FixtureIds.USER_GRANT_A, FixtureIds.ORG_X))
        .isEqualTo(MemberRole.MEMBER.name());
    assertThat(
            count(
                "select count(*) from organization_members where user_id = ?",
                FixtureIds.USER_NOACCESS))
        .isZero();
    assertThat(count("select count(*) from users where id = ?", FixtureIds.USER_NOACCESS))
        .isEqualTo(1);
    // grant-a: a project-A grant and nothing else; outsider-x: none
    assertThat(
            count(
                "select count(*) from permission_grant where user_id = ? and scope_id = ?",
                FixtureIds.USER_GRANT_A,
                FixtureIds.PROJECT_A))
        .isEqualTo(1);
    assertThat(
            count(
                "select count(*) from permission_grant where user_id = ?",
                FixtureIds.USER_OUTSIDER_X))
        .isZero();
    // member-y belongs to org Y only
    assertThat(
            jdbc.queryForList(
                "select organization_id from organization_members where user_id = ?",
                UUID.class,
                FixtureIds.USER_MEMBER_Y))
        .containsExactly(FixtureIds.ORG_Y);
  }

  @Test
  @DisplayName("AC1: ids are the fixed constants and emails are synthetic @example.test")
  void fixedIdsAndSyntheticEmails() {
    assertThat(count("select count(*) from projects where id = ?", FixtureIds.PROJECT_A))
        .isEqualTo(1);
    assertThat(count("select count(*) from environments where id = ?", FixtureIds.ENV_A_PROD))
        .isEqualTo(1);
    assertThat(count("select count(*) from feature_flags where id = ?", FixtureIds.FLAG_A1))
        .isEqualTo(1);
    List<String> emails =
        jdbc.queryForList("select email from users where id in (" + inList() + ")", String.class);
    assertThat(emails).hasSize(FixtureIds.ALL_USERS.size());
    assertThat(emails).allMatch(e -> e.endsWith("@example.test"));
    assertThat(emails).contains("user-a@example.test");
  }

  @Test
  @DisplayName("AC1: PROD change windows cover in-day, midnight-wrapping and unlimited")
  void changeWindows() {
    assertThat(window(FixtureIds.ENV_A_PROD)).containsExactly(9, 17);
    assertThat(window(FixtureIds.ENV_B_PROD)).containsExactly(22, 6);
    assertThat(window(FixtureIds.ENV_C_PROD)).containsExactly(null, null);
    assertThat(window(FixtureIds.ENV_A_DEV)).containsExactly(null, null);
  }

  @Test
  @DisplayName("AC2: two consecutive loads give an identical DB (0 diffs), Clock fixed")
  void deterministicAcrossLoads() {
    List<String> first = SyntheticFixture.snapshot(jdbc);
    assertThat(first).isNotEmpty();

    SyntheticFixture.load(jdbc);
    List<String> second = SyntheticFixture.snapshot(jdbc);

    assertThat(second).isEqualTo(first);
    // clock-independent: every timestamp is the fixed instant, never "now"
    assertThat(first.stream().filter(r -> r.contains("2026-01-15T10:00Z")).count()).isPositive();
    assertThat(SyntheticFixture.CLOCK.instant()).isEqualTo(SyntheticFixture.CLOCK.instant());
  }

  @Test
  @DisplayName("clear() removes only fixture rows and load() restores them")
  void clearThenLoad() {
    List<String> before = SyntheticFixture.snapshot(jdbc);
    SyntheticFixture.clear(jdbc);
    assertThat(SyntheticFixture.snapshot(jdbc)).isEmpty();
    SyntheticFixture.load(jdbc);
    assertThat(SyntheticFixture.snapshot(jdbc)).isEqualTo(before);
  }

  private String roleOf(UUID user, UUID org) {
    return jdbc.queryForObject(
        "select role from organization_members where user_id = ? and organization_id = ?",
        String.class,
        user,
        org);
  }

  private List<Integer> window(UUID env) {
    Map<String, Object> row =
        jdbc.queryForMap(
            "select change_window_start_hour s, change_window_end_hour e from environments"
                + " where id = ?",
            env);
    java.util.ArrayList<Integer> out = new java.util.ArrayList<>();
    out.add((Integer) row.get("s"));
    out.add((Integer) row.get("e"));
    return out;
  }

  private String inList() {
    return FixtureIds.ALL_USERS.stream().map(u -> "'" + u + "'").collect(Collectors.joining(","));
  }
}
