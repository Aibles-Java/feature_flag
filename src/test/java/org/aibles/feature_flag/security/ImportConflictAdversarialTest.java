package org.aibles.feature_flag.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.aibles.feature_flag.domain.entity.User;
import org.aibles.feature_flag.domain.enums.FlagValueType;
import org.aibles.feature_flag.domain.enums.ImportConflictStrategy;
import org.aibles.feature_flag.dto.request.ImportEnvironmentRequest;
import org.aibles.feature_flag.dto.request.UpdateFlagStateRequest;
import org.aibles.feature_flag.dto.response.EnvironmentSnapshotResponse;
import org.aibles.feature_flag.dto.response.ImportResultResponse;
import org.aibles.feature_flag.repository.FeatureFlagRepository;
import org.aibles.feature_flag.repository.FlagEnvironmentStateRepository;
import org.aibles.feature_flag.service.EnvironmentTransferService;
import org.aibles.feature_flag.service.EvaluationCacheService;
import org.aibles.feature_flag.service.FeatureFlagService;
import org.aibles.feature_flag.testsupport.FixedClocksTestConfig;
import org.aibles.feature_flag.testsupport.FixtureIds;
import org.aibles.feature_flag.testsupport.SyntheticFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * S-2.13 independent QA (T-IMP-2): conflict position matrix (first / middle / last, with a new flag
 * and a SKIPPED entry in the mix), HTTP 409 through the real advice, non-optimistic DB failure,
 * large import.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.allow-bean-definition-overriding=true",
      "spring.datasource.url=jdbc:h2:mem:import-conflict-adv-testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;"
          + "MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=KEY,VALUE;"
          + "CASE_INSENSITIVE_IDENTIFIERS=TRUE"
    })
@ActiveProfiles("test")
@Import(FixedClocksTestConfig.class)
class ImportConflictAdversarialTest {

  private static final UUID ENV = FixtureIds.ENV_A_DEV;
  private static final String[] KEYS = {
    "tst-flag-checkout", "tst-flag-theme", "tst-flag-page-size", "tst-flag-limits"
  };
  private static final UUID[] IDS = {
    FixtureIds.FLAG_A1, FixtureIds.FLAG_A2, FixtureIds.FLAG_A3, FixtureIds.FLAG_A4
  };

  @Value("${local.server.port}")
  int port;

  @Autowired EnvironmentTransferService service;
  @Autowired FeatureFlagService flagService;
  @Autowired FlagEnvironmentStateRepository stateRepository;
  @Autowired FeatureFlagRepository flagRepository;
  @Autowired JdbcTemplate jdbc;
  @Autowired JwtTokenProvider jwt;
  @MockitoSpyBean EvaluationCacheService cache;

  private Object serviceTarget;

  @BeforeEach
  void load() {
    Object t = AopProxyUtils.getSingletonTarget(service);
    serviceTarget = t == null ? service : t;
    SyntheticFixture.load(jdbc);
    jdbc.update("delete from audit_log where org_id = ?", FixtureIds.ORG_X);
    cleanNew();
    SecurityContextHolder.setContext(ownerContext());
  }

  @AfterEach
  void clear() {
    ReflectionTestUtils.setField(serviceTarget, "flagStateRepository", stateRepository);
    ReflectionTestUtils.setField(serviceTarget, "featureFlagRepository", flagRepository);
    SecurityContextHolder.clearContext();
    cleanNew();
  }

  private void cleanNew() {
    jdbc.update(
        "delete from flag_environment_states where feature_flag_id in"
            + " (select id from feature_flags where key like 'qa-%')");
    jdbc.update("delete from feature_flags where key like 'qa-%'");
  }

  private static User owner() {
    return User.builder()
        .id(FixtureIds.USER_OWNER_X)
        .email("tst@example.test")
        .passwordHash("x")
        .build();
  }

  private static SecurityContext ownerContext() {
    return new SecurityContextImpl(
        new UsernamePasswordAuthenticationToken(UserPrincipal.from(owner()), null));
  }

  private void put(UUID flag, String value) throws Exception {
    var pool = Executors.newSingleThreadExecutor();
    try {
      pool.submit(
              () -> {
                SecurityContextHolder.setContext(ownerContext());
                try {
                  UpdateFlagStateRequest r = new UpdateFlagStateRequest();
                  r.setEnabled(true);
                  r.setValue(value);
                  flagService.updateState(flag, ENV, r);
                } finally {
                  SecurityContextHolder.clearContext();
                }
                return null;
              })
          .get(30, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }
  }

  private void raceAfterReadOf(UUID flag, String v) {
    ReflectionTestUtils.setField(
        serviceTarget,
        "flagStateRepository",
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {FlagEnvironmentStateRepository.class},
            (p, m, a) -> {
              Object res;
              try {
                res = m.invoke(stateRepository, a);
              } catch (InvocationTargetException e) {
                throw e.getCause();
              }
              if (m.getName().equals("findByFeatureFlagIdAndEnvironmentId") && flag.equals(a[0])) {
                try {
                  put(flag, v);
                } catch (Exception e) {
                  throw new IllegalStateException("competing PUT failed: " + e, e);
                }
              }
              return res;
            }));
  }

  private static ImportEnvironmentRequest.FlagEntry entry(
      String key, FlagValueType type, boolean enabled, String value) {
    var e = new ImportEnvironmentRequest.FlagEntry();
    e.setKey(key);
    e.setName(key);
    e.setValueType(type);
    e.setEnabled(enabled);
    e.setValue(value);
    e.setRolloutPercent(70);
    return e;
  }

  private static ImportEnvironmentRequest req(
      List<ImportEnvironmentRequest.FlagEntry> flags, boolean dry) {
    var s = new ImportEnvironmentRequest.Snapshot();
    s.setSchemaVersion(EnvironmentSnapshotResponse.SCHEMA_VERSION);
    s.setFlags(flags);
    var r = new ImportEnvironmentRequest();
    r.setConflictStrategy(ImportConflictStrategy.OVERWRITE);
    r.setDryRun(dry);
    r.setSnapshot(s);
    return r;
  }

  /** new flag, checkout, SKIPPED(page-size invalid int), theme, limits, new flag at the end. */
  private static ImportEnvironmentRequest mixed() {
    return req(
        List.of(
            entry("qa-first-new", FlagValueType.BOOLEAN, true, "true"),
            entry(KEYS[0], FlagValueType.BOOLEAN, false, "false"),
            entry(KEYS[2], FlagValueType.INTEGER, true, "not-an-int"),
            entry(KEYS[1], FlagValueType.STRING, true, "imported"),
            entry(KEYS[3], FlagValueType.JSON, true, "{\"imported\":true}"),
            entry("qa-last-new", FlagValueType.BOOLEAN, true, "true")),
        false);
  }

  private List<Map<String, Object>> stateRows() {
    return jdbc.queryForList(
        "select feature_flag_id, environment_id, enabled, value, rollout_percent, version from"
            + " flag_environment_states order by feature_flag_id, environment_id");
  }

  private int count(String sql) {
    return jdbc.queryForObject(sql, Integer.class);
  }

  /**
   * idx: 0 first updated entry, 1 a middle one, 3 the last updated entry (followed by a new flag).
   */
  @ParameterizedTest(name = "conflict on entry index {0}")
  @ValueSource(ints = {0, 1, 3})
  @DisplayName("conflict anywhere: nothing from the import persists, the PUT survives")
  void conflictPositionMatrix(int idx) throws Exception {
    List<Map<String, Object>> before = stateRows();
    String putValue = new String[] {"false", "from-put", "42", "{\"from\":\"put\"}"}[idx];
    raceAfterReadOf(IDS[idx], putValue);

    assertThatThrownBy(() -> service.importSnapshot(ENV, mixed()))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);

    List<Map<String, Object>> after = stateRows();
    // only the raced row differs from the pre-import state, and it carries the PUT's value
    List<Map<String, Object>> diff = new ArrayList<>(after);
    diff.removeAll(before);
    assertThat(diff).hasSize(1);
    assertThat(diff.get(0).get("value")).isEqualTo(putValue);
    assertThat(((Number) diff.get(0).get("version")).longValue()).isEqualTo(1L);
    assertThat(after).hasSameSizeAs(before); // no sibling-env rows for new flags
    assertThat(count("select count(*) from feature_flags where key like 'qa-%'")).isZero();
    assertThat(count("select count(*) from audit_log where action = 'IMPORT'")).isZero();
    verify(cache, never()).evictAfterCommit(FixtureIds.ENV_A_STG);
    verify(cache, never()).evictAfterCommit(FixtureIds.ENV_A_PROD);
  }

  @Test
  @DisplayName("no conflict with SKIPPED in the mix: writes, versions +1 only on written states")
  void noConflictMixed() {
    long v0 =
        jdbc.queryForObject(
            "select version from flag_environment_states where"
                + " feature_flag_id = ? and environment_id = ?",
            Long.class,
            IDS[0],
            ENV);
    long vSkip =
        jdbc.queryForObject(
            "select version from flag_environment_states where"
                + " feature_flag_id = ? and environment_id = ?",
            Long.class,
            IDS[2],
            ENV);
    ImportResultResponse r = service.importSnapshot(ENV, mixed());
    assertThat(r.getSummary().getSkipped()).isEqualTo(1);
    assertThat(r.getSummary().getCreated()).isEqualTo(2);
    assertThat(r.getSummary().getUpdated()).isEqualTo(3);
    assertThat(
            (long)
                jdbc.queryForObject(
                    "select version from flag_environment_states where feature_flag_id = ? and"
                        + " environment_id = ?",
                    Long.class,
                    IDS[0],
                    ENV))
        .isEqualTo(v0 + 1);
    assertThat(
            (long)
                jdbc.queryForObject(
                    "select version from flag_environment_states where feature_flag_id = ? and"
                        + " environment_id = ?",
                    Long.class,
                    IDS[2],
                    ENV))
        .isEqualTo(vSkip);
    assertThat(count("select count(*) from audit_log where action = 'IMPORT'")).isEqualTo(1);
  }

  @Test
  @DisplayName("HTTP: conflict surfaces as 409 problem+json through the real advice")
  void http409() throws Exception {
    raceAfterReadOf(IDS[1], "from-put");
    String email =
        jdbc.queryForObject(
            "select email from users where id = ?", String.class, FixtureIds.USER_OWNER_X);
    String token =
        jwt.generateToken(
            UserPrincipal.from(
                User.builder().id(FixtureIds.USER_OWNER_X).email(email).passwordHash("x").build()));
    HttpRequest hr =
        HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/v1/environments/" + ENV + "/import"))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(new ObjectMapper().writeValueAsString(mixed())))
            .build();
    HttpResponse<String> resp =
        HttpClient.newHttpClient().send(hr, HttpResponse.BodyHandlers.ofString());
    assertThat(resp.statusCode()).as(resp.body()).isEqualTo(409);
    assertThat(count("select count(*) from feature_flags where key like 'qa-%'")).isZero();
    assertThat(count("select count(*) from audit_log where action = 'IMPORT'")).isZero();
  }

  @Test
  @DisplayName("non-optimistic DB error (unique key created concurrently): all rolled back")
  void uniqueViolationRollsBackEverything() {
    List<Map<String, Object>> before = stateRows();
    // after the import's lookup of the new flag finds nothing, another tx commits the same key
    ReflectionTestUtils.setField(
        serviceTarget,
        "featureFlagRepository",
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {FeatureFlagRepository.class},
            (p, m, a) -> {
              Object res;
              try {
                res = m.invoke(flagRepository, a);
              } catch (InvocationTargetException e) {
                throw e.getCause();
              }
              if (m.getName().equals("findByProjectIdAndKey") && "qa-last-new".equals(a[1])) {
                jdbc.update(
                    "insert into feature_flags (id, project_id, name, key, value_type, archived,"
                        + " created_at, updated_at) values (?, ?, 'x', 'qa-last-new', 'BOOLEAN',"
                        + " false, current_timestamp, current_timestamp)",
                    UUID.randomUUID(),
                    FixtureIds.PROJECT_A);
              }
              return res;
            }));

    assertThatThrownBy(() -> service.importSnapshot(ENV, mixed()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);

    jdbc.update("delete from feature_flags where key = 'qa-last-new'");
    assertThat(stateRows()).isEqualTo(before);
    assertThat(count("select count(*) from feature_flags where key like 'qa-%'")).isZero();
    assertThat(count("select count(*) from audit_log where action = 'IMPORT'")).isZero();
  }

  @Test
  @DisplayName("large import: 2000 creates then 2000 overwrites completes (single flush)")
  void largeImport() {
    List<ImportEnvironmentRequest.FlagEntry> flags = new ArrayList<>();
    for (int i = 0; i < 2000; i++) {
      flags.add(entry("qa-bulk-" + i, FlagValueType.STRING, true, "v1"));
    }
    long t0 = System.nanoTime();
    ImportResultResponse a = service.importSnapshot(ENV, req(flags, false));
    long t1 = System.nanoTime();
    assertThat(a.getSummary().getCreated()).isEqualTo(2000);
    List<ImportEnvironmentRequest.FlagEntry> flags2 = new ArrayList<>();
    for (int i = 0; i < 2000; i++) {
      flags2.add(entry("qa-bulk-" + i, FlagValueType.STRING, true, "v2"));
    }
    ImportResultResponse b = service.importSnapshot(ENV, req(flags2, false));
    long t2 = System.nanoTime();
    assertThat(b.getSummary().getUpdated()).isEqualTo(2000);
    System.out.println(
        "QA-PERF create ms="
            + (t1 - t0) / 1_000_000
            + " overwrite2000 ms="
            + (t2 - t1) / 1_000_000);
    assertThat(count("select count(*) from flag_environment_states where value = 'v2'"))
        .isEqualTo(2000);
  }
}
