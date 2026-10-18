package org.aibles.feature_flag.flags;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManagerFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.aibles.feature_flag.domain.entity.User;
import org.aibles.feature_flag.domain.enums.Action;
import org.aibles.feature_flag.dto.response.FlagMatrixRowResponse;
import org.aibles.feature_flag.dto.response.FlagStateResponse;
import org.aibles.feature_flag.dto.response.PageResponse;
import org.aibles.feature_flag.exception.UnauthorizedException;
import org.aibles.feature_flag.security.UserPrincipal;
import org.aibles.feature_flag.service.FlagMatrixService;
import org.aibles.feature_flag.service.impl.PermissionService;
import org.aibles.feature_flag.testsupport.FixtureIds;
import org.aibles.feature_flag.testsupport.SyntheticFixture;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.ActiveProfiles;

/**
 * S-2.5: GET /flags/environment-states against the real PDP and the S-0.0 synthetic fixture. H2 in
 * PostgreSQL mode; confirm on real PostgreSQL before G2.
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:matrix-testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;"
          + "MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=KEY,VALUE;"
          + "CASE_INSENSITIVE_IDENTIFIERS=TRUE",
      "spring.jpa.properties.hibernate.generate_statistics=true"
    })
@ActiveProfiles("test")
class FlagMatrixIntegrationTest {

  @Autowired FlagMatrixService service;
  @Autowired PermissionService permissionService;
  @Autowired JdbcTemplate jdbc;
  @Autowired EntityManagerFactory emf;

  @BeforeEach
  void load() {
    removeBulk();
    SyntheticFixture.load(jdbc);
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
    removeBulk();
  }

  private void as(UUID userId) {
    UserPrincipal principal =
        UserPrincipal.from(
            User.builder().id(userId).email("tst@example.test").passwordHash("x").build());
    SecurityContextHolder.setContext(
        new SecurityContextImpl(new UsernamePasswordAuthenticationToken(principal, null)));
  }

  private static UUID uuid(String s) {
    return UUID.nameUUIDFromBytes(s.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  @DisplayName("AC1 / T-IDOR-3: no access, other-org member and same-org-no-grant -> 403")
  void noPermission_is403() {
    for (UUID user :
        List.of(FixtureIds.USER_MEMBER_Y, FixtureIds.USER_OUTSIDER_X, FixtureIds.USER_NOACCESS)) {
      as(user);
      assertThatThrownBy(() -> service.getMatrix(FixtureIds.PROJECT_A, 0, 50))
          .as("user %s", user)
          .isInstanceOf(UnauthorizedException.class);
    }
    // grant-only on project A reads A but not sibling project B
    as(FixtureIds.USER_GRANT_A);
    assertThat(service.getMatrix(FixtureIds.PROJECT_A, 0, 50).getContent()).isNotEmpty();
    assertThatThrownBy(() -> service.getMatrix(FixtureIds.PROJECT_B, 0, 50))
        .isInstanceOf(UnauthorizedException.class);
  }

  @Test
  @DisplayName("matrix shape: one row per flag, one state per env, only project A data")
  void returnsProjectMatrix() {
    as(FixtureIds.USER_VIEWER_X);
    PageResponse<FlagMatrixRowResponse> r = service.getMatrix(FixtureIds.PROJECT_A, 0, 50);
    assertThat(r.getTotalElements()).isEqualTo(4);
    assertThat(r.getContent()).hasSize(4);
    for (FlagMatrixRowResponse row : r.getContent()) {
      assertThat(row.getFlag().getProjectId()).isEqualTo(FixtureIds.PROJECT_A);
      assertThat(row.getStates())
          .extracting(FlagStateResponse::getEnvironmentId)
          .containsExactlyInAnyOrder(
              FixtureIds.ENV_A_DEV, FixtureIds.ENV_A_STG, FixtureIds.ENV_A_PROD);
    }
  }

  @Test
  @DisplayName("archived flags are excluded, like the existing list endpoint")
  void archivedExcluded() {
    jdbc.update("update feature_flags set archived = true where id = ?", FixtureIds.FLAG_A1);
    as(FixtureIds.USER_VIEWER_X);
    PageResponse<FlagMatrixRowResponse> r = service.getMatrix(FixtureIds.PROJECT_A, 0, 50);
    assertThat(r.getTotalElements()).isEqualTo(3);
    assertThat(r.getContent())
        .extracting(row -> row.getFlag().getId())
        .doesNotContain(FixtureIds.FLAG_A1);
  }

  @Test
  @DisplayName("AC2 / T-IDOR-4: an anomalous state linking flag A to env of B is never returned")
  void anomalousCrossProjectState_notReturned() {
    jdbc.update(
        "insert into flag_environment_states (id, feature_flag_id, environment_id, enabled,"
            + " value, rollout_percent, created_at, updated_at) values (?, ?, ?, true, ?, 100, ?, ?)",
        uuid("anomalous-state"),
        FixtureIds.FLAG_A1,
        FixtureIds.ENV_B_DEV,
        "anomalous",
        SyntheticFixture.AT,
        SyntheticFixture.AT);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from flag_environment_states where id = ?",
                Long.class,
                uuid("anomalous-state")))
        .isEqualTo(1);

    as(FixtureIds.USER_OWNER_X);
    PageResponse<FlagMatrixRowResponse> r = service.getMatrix(FixtureIds.PROJECT_A, 0, 50);
    List<FlagStateResponse> all =
        r.getContent().stream().flatMap(row -> row.getStates().stream()).toList();
    assertThat(all)
        .extracting(FlagStateResponse::getEnvironmentId)
        .doesNotContain(FixtureIds.ENV_B_DEV);
    assertThat(all).extracting(FlagStateResponse::getValue).doesNotContain("anomalous");
    assertThat(all).hasSize(4 * 3);
  }

  @Test
  @DisplayName(
      "AC3 / T-PAGE-1 + AC4: 100 flags x 20 envs; size=1000 clamps to 100; data path (excluding authorization) <= 2 statements")
  void clampAndConstantQueryCount() {
    seedBulk(); // project A: 100 flags x 20 envs
    as(FixtureIds.USER_OWNER_X);
    Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();

    // statements issued by the authorization step alone, so they are not counted against the data
    // path
    // warm any permission cache first so the subtraction cannot over-credit the data path
    permissionService.check(
        Action.FLAG_READ, PermissionService.ResourceRef.project(FixtureIds.PROJECT_A));
    stats.clear();
    permissionService.check(
        Action.FLAG_READ, PermissionService.ResourceRef.project(FixtureIds.PROJECT_A));
    long permissionStatements = stats.getPrepareStatementCount();

    stats.clear();
    PageResponse<FlagMatrixRowResponse> big = service.getMatrix(FixtureIds.PROJECT_A, 0, 1000);
    long totalStatements = stats.getPrepareStatementCount();
    long dataStatements = totalStatements - permissionStatements;
    // honesty guard: the data path really issues the flag page + the states query
    assertThat(dataStatements)
        .as("raw total %s, perm %s", totalStatements, permissionStatements)
        .isGreaterThanOrEqualTo(2);

    assertThat(big.getSize()).isEqualTo(100);
    assertThat(big.getContent()).hasSize(100);
    assertThat(big.getTotalElements()).isEqualTo(100);
    assertThat(big.getTotalPages()).isEqualTo(1);
    assertThat(big.getContent()).allSatisfy(row -> assertThat(row.getStates()).hasSize(20));
    assertThat(dataStatements).as("data-path SQL statements").isLessThanOrEqualTo(2);

    // default page size 50, paginated by flag, no overlap between pages
    PageResponse<FlagMatrixRowResponse> p0 = service.getMatrix(FixtureIds.PROJECT_A, 0, 50);
    PageResponse<FlagMatrixRowResponse> p1 = service.getMatrix(FixtureIds.PROJECT_A, 1, 50);
    assertThat(p0.getContent()).hasSize(50);
    assertThat(p1.getContent()).hasSize(50);
    assertThat(p0.getTotalPages()).isEqualTo(2);
    List<UUID> ids0 = p0.getContent().stream().map(r -> r.getFlag().getId()).toList();
    assertThat(p1.getContent())
        .extracting(r -> r.getFlag().getId())
        .doesNotContainAnyElementsOf(ids0);
    assertThat(service.getMatrix(FixtureIds.PROJECT_A, 5, 50).getContent()).isEmpty();
    assertThat(service.getMatrix(FixtureIds.PROJECT_A, 5, 50).getTotalElements()).isEqualTo(100);
  }

  // 4 fixture flags + 96 bulk = 100 flags; 3 fixture envs + 17 bulk = 20 envs in project A.
  private void seedBulk() {
    List<UUID> envs =
        new ArrayList<>(List.of(FixtureIds.ENV_A_DEV, FixtureIds.ENV_A_STG, FixtureIds.ENV_A_PROD));
    List<Object[]> envRows = new ArrayList<>();
    for (int i = 0; i < 17; i++) {
      UUID id = uuid("bulk-env-" + i);
      envs.add(id);
      envRows.add(
          new Object[] {
            id,
            FixtureIds.PROJECT_A,
            "bulk-env-" + i,
            "synthetic",
            "DEVELOPMENT",
            SyntheticFixture.AT,
            SyntheticFixture.AT
          });
    }
    jdbc.batchUpdate(
        "insert into environments (id, project_id, name, description, type, created_at,"
            + " updated_at) values (?, ?, ?, ?, ?, ?, ?)",
        envRows);
    List<UUID> flags =
        new ArrayList<>(
            List.of(
                FixtureIds.FLAG_A1, FixtureIds.FLAG_A2, FixtureIds.FLAG_A3, FixtureIds.FLAG_A4));
    List<Object[]> flagRows = new ArrayList<>();
    for (int i = 0; i < 96; i++) {
      UUID id = uuid("bulk-flag-" + i);
      flags.add(id);
      flagRows.add(
          new Object[] {
            id,
            FixtureIds.PROJECT_A,
            "bulk-flag-" + i,
            "bulk-flag-" + i,
            "synthetic",
            "BOOLEAN",
            SyntheticFixture.AT.plusSeconds(i + 1),
            SyntheticFixture.AT
          });
    }
    jdbc.batchUpdate(
        "insert into feature_flags (id, project_id, name, key, description, value_type, archived,"
            + " created_at, updated_at) values (?, ?, ?, ?, ?, ?, false, ?, ?)",
        flagRows);
    List<Object[]> stateRows = new ArrayList<>();
    for (UUID f : flags) {
      for (UUID e : envs) {
        boolean preexisting =
            List.of(FixtureIds.FLAG_A1, FixtureIds.FLAG_A2, FixtureIds.FLAG_A3, FixtureIds.FLAG_A4)
                    .contains(f)
                && List.of(FixtureIds.ENV_A_DEV, FixtureIds.ENV_A_STG, FixtureIds.ENV_A_PROD)
                    .contains(e);
        if (preexisting) continue;
        stateRows.add(
            new Object[] {uuid("s-" + f + e), f, e, SyntheticFixture.AT, SyntheticFixture.AT});
      }
    }
    jdbc.batchUpdate(
        "insert into flag_environment_states (id, feature_flag_id, environment_id, enabled, value,"
            + " rollout_percent, created_at, updated_at) values (?, ?, ?, false, 'true', 100, ?, ?)",
        stateRows);
  }

  private void removeBulk() {
    String envIds =
        java.util.stream.IntStream.range(0, 17)
            .mapToObj(i -> "'" + uuid("bulk-env-" + i) + "'")
            .collect(Collectors.joining(","));
    jdbc.update("delete from flag_environment_states where environment_id in (" + envIds + ")");
    jdbc.update(
        "delete from flag_environment_states where feature_flag_id in"
            + " (select id from feature_flags where key like 'bulk-flag-%')");
    jdbc.update("delete from feature_flags where key like 'bulk-flag-%'");
    jdbc.update("delete from environments where id in (" + envIds + ")");
  }
}
