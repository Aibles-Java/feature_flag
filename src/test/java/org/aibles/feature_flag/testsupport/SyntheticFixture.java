package org.aibles.feature_flag.testsupport;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.aibles.feature_flag.domain.enums.EnvType;
import org.aibles.feature_flag.domain.enums.FlagValueType;
import org.aibles.feature_flag.domain.enums.MemberRole;
import org.aibles.feature_flag.domain.enums.ScopeType;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Deterministic, synthetic multi-org/multi-project dataset for BE tests (S-0.0, T-IDOR-*).
 *
 * <p>Rows are written with plain JDBC, not the repositories: the entities use
 * {@code @GeneratedValue(UUID)}, which ignores a preassigned id, and the fixture's whole point is
 * fixed ids ({@link FixtureIds}). Every timestamp is {@link #AT} (derived from {@link #CLOCK}),
 * never the wall clock, so {@link #snapshot} is identical across loads.
 *
 * <p>Usage: {@code SyntheticFixture.load(jdbc)} in {@code @BeforeEach}. {@code load} first removes
 * any previous fixture rows (by id), so it is idempotent and never touches other rows.
 *
 * <p>Synthetic only: no PII or card data; emails are {@code *@example.test}, the password hash is a
 * non-credential placeholder (cannot authenticate; mint test JWTs from the test secret instead).
 */
public final class SyntheticFixture {

  /** The fixed clock for the fixture; inject the same one into time-dependent code under test. */
  public static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-01-15T10:00:00Z"), ZoneOffset.UTC);

  /** Timestamp stamped on every fixture row. */
  public static final OffsetDateTime AT = OffsetDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC);

  /** Not a bcrypt/real hash: login with it is impossible by construction. */
  static final String PLACEHOLDER_HASH = "{synthetic}not-a-real-credential";

  private static final String[] TABLES = {
    "users",
    "organizations",
    "organization_members",
    "projects",
    "environments",
    "feature_flags",
    "flag_environment_states",
    "permission_grant"
  };

  private SyntheticFixture() {}

  /** Idempotent: removes previous fixture rows, then inserts the full dataset. */
  public static void load(JdbcTemplate jdbc) {
    clear(jdbc);
    users(jdbc);
    orgs(jdbc);
    projects(jdbc);
    environments(jdbc);
    flags(jdbc);
    grants(jdbc);
  }

  /** Deletes exactly the fixture rows (by fixed id) in FK order. */
  public static void clear(JdbcTemplate jdbc) {
    String flags = ids(allFlags());
    String envs = ids(allEnvs());
    String projects = ids(FixtureIds.ALL_PROJECTS);
    String orgs = ids(List.of(FixtureIds.ORG_X, FixtureIds.ORG_Y));
    String users = ids(FixtureIds.ALL_USERS);
    jdbc.update("delete from flag_environment_states where feature_flag_id in (" + flags + ")");
    jdbc.update("delete from permission_grant where user_id in (" + users + ")");
    jdbc.update("delete from feature_flags where id in (" + flags + ")");
    jdbc.update("delete from environments where id in (" + envs + ")");
    jdbc.update("delete from projects where id in (" + projects + ")");
    jdbc.update("delete from organization_members where organization_id in (" + orgs + ")");
    jdbc.update("delete from organizations where id in (" + orgs + ")");
    jdbc.update("delete from users where id in (" + users + ")");
  }

  /**
   * Canonical, ordered, string form of every fixture-owned table row; two equal snapshots mean a
   * 0-diff database (AC2).
   */
  public static List<String> snapshot(JdbcTemplate jdbc) {
    List<String> out = new ArrayList<>();
    String flags = ids(allFlags());
    String envs = ids(allEnvs());
    String projects = ids(FixtureIds.ALL_PROJECTS);
    String orgs = ids(List.of(FixtureIds.ORG_X, FixtureIds.ORG_Y));
    String users = ids(FixtureIds.ALL_USERS);
    String[] where = {
      "id in (" + users + ")",
      "id in (" + orgs + ")",
      "organization_id in (" + orgs + ")",
      "id in (" + projects + ")",
      "id in (" + envs + ")",
      "id in (" + flags + ")",
      "feature_flag_id in (" + flags + ")",
      "user_id in (" + users + ")"
    };
    for (int i = 0; i < TABLES.length; i++) {
      List<Map<String, Object>> rows =
          jdbc.queryForList("select * from " + TABLES[i] + " where " + where[i]);
      for (Map<String, Object> row : rows) {
        out.add(TABLES[i] + new java.util.TreeMap<>(row));
      }
    }
    java.util.Collections.sort(out);
    return out;
  }

  // ---------------------------------------------------------------- inserts

  private static void users(JdbcTemplate j) {
    user(j, FixtureIds.USER_OWNER_X, "user-a@example.test", "Owner", "X");
    user(j, FixtureIds.USER_ADMIN_X, "user-b@example.test", "Admin", "X");
    user(j, FixtureIds.USER_VIEWER_X, "user-c@example.test", "Viewer", "X");
    user(j, FixtureIds.USER_GRANT_A, "user-d@example.test", "Grant", "A");
    user(j, FixtureIds.USER_OUTSIDER_X, "user-e@example.test", "Outsider", "X");
    user(j, FixtureIds.USER_MEMBER_Y, "user-f@example.test", "Member", "Y");
    user(j, FixtureIds.USER_NOACCESS, "user-g@example.test", "No", "Access");
  }

  private static void user(JdbcTemplate j, UUID id, String email, String first, String last) {
    j.update(
        "insert into users (id, email, password_hash, first_name, last_name, enabled, created_at,"
            + " updated_at) values (?, ?, ?, ?, ?, true, ?, ?)",
        id,
        email,
        PLACEHOLDER_HASH,
        first,
        last,
        AT,
        AT);
  }

  private static void orgs(JdbcTemplate j) {
    org(j, FixtureIds.ORG_X, "tst-org-x");
    org(j, FixtureIds.ORG_Y, "tst-org-y");
    member(j, FixtureIds.ORG_X, FixtureIds.USER_OWNER_X, MemberRole.OWNER);
    member(j, FixtureIds.ORG_X, FixtureIds.USER_ADMIN_X, MemberRole.ADMIN);
    member(j, FixtureIds.ORG_X, FixtureIds.USER_VIEWER_X, MemberRole.VIEWER);
    member(j, FixtureIds.ORG_X, FixtureIds.USER_GRANT_A, MemberRole.MEMBER);
    member(j, FixtureIds.ORG_X, FixtureIds.USER_OUTSIDER_X, MemberRole.MEMBER);
    member(j, FixtureIds.ORG_Y, FixtureIds.USER_MEMBER_Y, MemberRole.VIEWER);
  }

  private static void org(JdbcTemplate j, UUID id, String slug) {
    j.update(
        "insert into organizations (id, name, slug, created_at, updated_at)"
            + " values (?, ?, ?, ?, ?)",
        id,
        slug,
        slug,
        AT,
        AT);
  }

  private static void member(JdbcTemplate j, UUID org, UUID user, MemberRole role) {
    j.update(
        "insert into organization_members (id, organization_id, user_id, role, created_at)"
            + " values (?, ?, ?, ?, ?)",
        FixtureIds.memberId(org, user),
        org,
        user,
        role.name(),
        AT);
  }

  private static void projects(JdbcTemplate j) {
    project(j, FixtureIds.PROJECT_A, FixtureIds.ORG_X, "tst-proj-a");
    project(j, FixtureIds.PROJECT_B, FixtureIds.ORG_X, "tst-proj-b");
    project(j, FixtureIds.PROJECT_C, FixtureIds.ORG_Y, "tst-proj-c");
    project(j, FixtureIds.PROJECT_D, FixtureIds.ORG_Y, "tst-proj-d");
  }

  private static void project(JdbcTemplate j, UUID id, UUID org, String name) {
    j.update(
        "insert into projects (id, organization_id, name, description, created_at, updated_at)"
            + " values (?, ?, ?, ?, ?, ?)",
        id,
        org,
        name,
        "synthetic " + name,
        AT,
        AT);
  }

  /** PROD windows: A [9,17) in-day, B [22,6) wraps midnight, C and D unlimited (null). */
  private static void environments(JdbcTemplate j) {
    env(j, FixtureIds.ENV_A_DEV, FixtureIds.PROJECT_A, "dev", EnvType.DEVELOPMENT, null, null);
    env(j, FixtureIds.ENV_A_STG, FixtureIds.PROJECT_A, "stg", EnvType.STAGING, null, null);
    env(j, FixtureIds.ENV_A_PROD, FixtureIds.PROJECT_A, "prod", EnvType.PRODUCTION, 9, 17);
    env(j, FixtureIds.ENV_B_DEV, FixtureIds.PROJECT_B, "dev", EnvType.DEVELOPMENT, null, null);
    env(j, FixtureIds.ENV_B_STG, FixtureIds.PROJECT_B, "stg", EnvType.STAGING, null, null);
    env(j, FixtureIds.ENV_B_PROD, FixtureIds.PROJECT_B, "prod", EnvType.PRODUCTION, 22, 6);
    env(j, FixtureIds.ENV_C_DEV, FixtureIds.PROJECT_C, "dev", EnvType.DEVELOPMENT, null, null);
    env(j, FixtureIds.ENV_C_STG, FixtureIds.PROJECT_C, "stg", EnvType.STAGING, null, null);
    env(j, FixtureIds.ENV_C_PROD, FixtureIds.PROJECT_C, "prod", EnvType.PRODUCTION, null, null);
    env(j, FixtureIds.ENV_D_DEV, FixtureIds.PROJECT_D, "dev", EnvType.DEVELOPMENT, null, null);
    env(j, FixtureIds.ENV_D_STG, FixtureIds.PROJECT_D, "stg", EnvType.STAGING, null, null);
    env(j, FixtureIds.ENV_D_PROD, FixtureIds.PROJECT_D, "prod", EnvType.PRODUCTION, null, null);
  }

  private static void env(
      JdbcTemplate j, UUID id, UUID project, String name, EnvType type, Integer from, Integer to) {
    j.update(
        "insert into environments (id, project_id, name, description, type,"
            + " change_window_start_hour, change_window_end_hour, change_window_timezone,"
            + " created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        id,
        project,
        name,
        "synthetic " + name,
        type.name(),
        from,
        to,
        from == null ? null : "UTC",
        AT,
        AT);
  }

  private static void flags(JdbcTemplate j) {
    flag(j, FixtureIds.FLAG_A1, FixtureIds.PROJECT_A, "tst-flag-checkout", FlagValueType.BOOLEAN);
    flag(j, FixtureIds.FLAG_A2, FixtureIds.PROJECT_A, "tst-flag-theme", FlagValueType.STRING);
    flag(j, FixtureIds.FLAG_A3, FixtureIds.PROJECT_A, "tst-flag-page-size", FlagValueType.INTEGER);
    flag(j, FixtureIds.FLAG_A4, FixtureIds.PROJECT_A, "tst-flag-limits", FlagValueType.JSON);
    flag(j, FixtureIds.FLAG_B1, FixtureIds.PROJECT_B, "tst-flag-search", FlagValueType.BOOLEAN);
    flag(j, FixtureIds.FLAG_C1, FixtureIds.PROJECT_C, "tst-flag-banner", FlagValueType.STRING);
  }

  /** Inserts the flag and a state for every env of its project (never across projects). */
  private static void flag(JdbcTemplate j, UUID id, UUID project, String key, FlagValueType type) {
    j.update(
        "insert into feature_flags (id, project_id, name, key, description, value_type, archived,"
            + " created_at, updated_at) values (?, ?, ?, ?, ?, ?, false, ?, ?)",
        id,
        project,
        key,
        key,
        "synthetic " + key,
        type.name(),
        AT,
        AT);
    List<Map<String, Object>> envs =
        j.queryForList(
            "select id, type from environments where project_id = ? order by id", project);
    for (Map<String, Object> e : envs) {
      boolean dev = EnvType.DEVELOPMENT.name().equals(e.get("type"));
      boolean prod = EnvType.PRODUCTION.name().equals(e.get("type"));
      UUID envId = (UUID) e.get("id");
      j.update(
          "insert into flag_environment_states (id, feature_flag_id, environment_id, enabled,"
              + " value, rollout_percent, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?)",
          FixtureIds.stateId(id, envId),
          id,
          envId,
          dev,
          neutralValue(type),
          prod ? 50 : 100,
          AT,
          AT);
    }
  }

  /** Neutral, secret-free values (test-strategy §7). */
  private static String neutralValue(FlagValueType type) {
    return switch (type) {
      case BOOLEAN -> "true";
      case STRING -> "blue";
      case INTEGER -> "42";
      case JSON -> "{\"limit\":10}";
    };
  }

  /** grant-a: ADMIN on project A only (org role stays MEMBER). */
  private static void grants(JdbcTemplate j) {
    j.update(
        "insert into permission_grant (id, user_id, scope_type, scope_id, role, created_at)"
            + " values (?, ?, ?, ?, ?, ?)",
        FixtureIds.grantId(FixtureIds.USER_GRANT_A, FixtureIds.PROJECT_A),
        FixtureIds.USER_GRANT_A,
        ScopeType.PROJECT.name(),
        FixtureIds.PROJECT_A,
        MemberRole.ADMIN.name(),
        AT);
  }

  // ---------------------------------------------------------------- helpers

  private static List<UUID> allFlags() {
    return List.of(
        FixtureIds.FLAG_A1,
        FixtureIds.FLAG_A2,
        FixtureIds.FLAG_A3,
        FixtureIds.FLAG_A4,
        FixtureIds.FLAG_B1,
        FixtureIds.FLAG_C1);
  }

  private static List<UUID> allEnvs() {
    return List.of(
        FixtureIds.ENV_A_DEV,
        FixtureIds.ENV_A_STG,
        FixtureIds.ENV_A_PROD,
        FixtureIds.ENV_B_DEV,
        FixtureIds.ENV_B_STG,
        FixtureIds.ENV_B_PROD,
        FixtureIds.ENV_C_DEV,
        FixtureIds.ENV_C_STG,
        FixtureIds.ENV_C_PROD,
        FixtureIds.ENV_D_DEV,
        FixtureIds.ENV_D_STG,
        FixtureIds.ENV_D_PROD);
  }

  /** UUID literals only (fixed constants, never user input), so inlining is injection-safe. */
  private static String ids(List<UUID> ids) {
    return ids.stream().map(u -> "'" + u + "'").collect(Collectors.joining(","));
  }
}
