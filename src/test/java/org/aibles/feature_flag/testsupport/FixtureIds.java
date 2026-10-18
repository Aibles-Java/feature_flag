package org.aibles.feature_flag.testsupport;

import java.util.List;
import java.util.UUID;

/**
 * Fixed identifiers of the synthetic multi-org/project fixture (S-0.0).
 *
 * <p>Every id is a constant so a test can assert a 403/404 against a named resource instead of a
 * random one, and so two loads of {@link SyntheticFixture} are byte-identical. Layout of the
 * topology (test-strategy §7):
 *
 * <pre>
 * org X  : projects A, B   (envs DEV/STG/PROD each)  flags A1..A4 (all 4 valueTypes), B1
 * org Y  : projects C, D   (envs DEV/STG/PROD each)  flag  C1
 * </pre>
 *
 * Synthetic data only: names carry the {@code tst-} prefix, emails are {@code *@example.test}.
 */
public final class FixtureIds {

  private FixtureIds() {}

  private static UUID id(int kind, int n) {
    return UUID.fromString(String.format("00000000-0000-4000-8000-%04d%08d", kind, n));
  }

  // Users (kind 1)
  public static final UUID USER_OWNER_X = id(1, 1);
  public static final UUID USER_ADMIN_X = id(1, 2);
  public static final UUID USER_VIEWER_X = id(1, 3);

  /** MEMBER of org X plus a PROJECT grant on project A. */
  public static final UUID USER_GRANT_A = id(1, 4);

  /** MEMBER of org X, no grant: the "same org, other project" case. */
  public static final UUID USER_OUTSIDER_X = id(1, 5);

  /** VIEWER of org Y only. */
  public static final UUID USER_MEMBER_Y = id(1, 6);

  /** Exists, belongs to no org. */
  public static final UUID USER_NOACCESS = id(1, 7);

  public static final List<UUID> ALL_USERS =
      List.of(
          USER_OWNER_X,
          USER_ADMIN_X,
          USER_VIEWER_X,
          USER_GRANT_A,
          USER_OUTSIDER_X,
          USER_MEMBER_Y,
          USER_NOACCESS);

  // Organizations (kind 2)
  public static final UUID ORG_X = id(2, 1);
  public static final UUID ORG_Y = id(2, 2);

  // Projects (kind 3)
  public static final UUID PROJECT_A = id(3, 1);
  public static final UUID PROJECT_B = id(3, 2);
  public static final UUID PROJECT_C = id(3, 3);
  public static final UUID PROJECT_D = id(3, 4);

  public static final List<UUID> ALL_PROJECTS = List.of(PROJECT_A, PROJECT_B, PROJECT_C, PROJECT_D);

  // Environments (kind 4): project A 01-03, B 11-13, C 21-23, D 31-33 (DEV, STG, PROD)
  public static final UUID ENV_A_DEV = id(4, 1);
  public static final UUID ENV_A_STG = id(4, 2);
  public static final UUID ENV_A_PROD = id(4, 3);
  public static final UUID ENV_B_DEV = id(4, 11);
  public static final UUID ENV_B_STG = id(4, 12);
  public static final UUID ENV_B_PROD = id(4, 13);
  public static final UUID ENV_C_DEV = id(4, 21);
  public static final UUID ENV_C_STG = id(4, 22);
  public static final UUID ENV_C_PROD = id(4, 23);
  public static final UUID ENV_D_DEV = id(4, 31);
  public static final UUID ENV_D_STG = id(4, 32);
  public static final UUID ENV_D_PROD = id(4, 33);

  // Flags (kind 5)
  public static final UUID FLAG_A1 = id(5, 1); // BOOLEAN
  public static final UUID FLAG_A2 = id(5, 2); // STRING
  public static final UUID FLAG_A3 = id(5, 3); // INTEGER
  public static final UUID FLAG_A4 = id(5, 4); // JSON
  public static final UUID FLAG_B1 = id(5, 11); // BOOLEAN
  public static final UUID FLAG_C1 = id(5, 21); // STRING

  // Flag states (kind 6), derived: stable per (flag, env) pair
  public static UUID stateId(UUID flag, UUID env) {
    return UUID.nameUUIDFromBytes(
        ("tst-state:" + flag + ":" + env).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  // Membership / grant rows (kind 7 / 8), derived
  static UUID memberId(UUID org, UUID user) {
    return UUID.nameUUIDFromBytes(
        ("tst-member:" + org + ":" + user).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  static UUID grantId(UUID user, UUID project) {
    return UUID.nameUUIDFromBytes(
        ("tst-grant:" + user + ":" + project).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }
}
