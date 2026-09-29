package org.aibles.feature_flag.hygiene;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.domain.entity.FeatureFlag;
import org.aibles.feature_flag.domain.entity.FlagEnvironmentState;
import org.aibles.feature_flag.domain.entity.Organization;
import org.aibles.feature_flag.domain.entity.Project;
import org.aibles.feature_flag.domain.enums.FlagValueType;
import org.aibles.feature_flag.repository.EnvironmentRepository;
import org.aibles.feature_flag.repository.FeatureFlagRepository;
import org.aibles.feature_flag.repository.FlagEnvironmentStateRepository;
import org.aibles.feature_flag.repository.OrganizationRepository;
import org.aibles.feature_flag.repository.ProjectRepository;
import org.aibles.feature_flag.service.EvaluationService;
import org.aibles.feature_flag.util.ApiKeyHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Issue #37 against a real database.
 *
 * <p>The first test is the one that matters most. The SDK evaluation path is
 * {@code @Transactional(readOnly = true)}, and the usage write joins it unless the repository
 * method declares {@code REQUIRES_NEW} — PostgreSQL rejects an UPDATE in a read-only transaction
 * outright. This asserts the timestamp is actually persisted through the real evaluation call,
 * rather than trusting the annotation.
 *
 * <p>Throttling is turned effectively off here ({@code 1ms}) so each test controls its own writes;
 * the throttle itself is covered by {@link FlagEvaluationTrackerTest}.
 */
@SpringBootTest(
    properties = {
      // NON_KEYWORDS=KEY,VALUE is mandatory: feature_flags.key is a reserved word in H2 2.x,
      // so inserting a flag fails with a syntax error without it. See
      // conventions/springboot4-jpa-test-quirks.md.
      "spring.datasource.url=jdbc:h2:mem:hygiene-testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=KEY,VALUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
      "app.hygiene.stale-after=1h",
      "app.hygiene.evaluation-touch-throttle=1ms"
    })
@ActiveProfiles("test")
class FlagHygieneIntegrationTest {

  @Autowired OrganizationRepository organizationRepository;
  @Autowired ProjectRepository projectRepository;
  @Autowired EnvironmentRepository environmentRepository;
  @Autowired FeatureFlagRepository featureFlagRepository;
  @Autowired FlagEnvironmentStateRepository flagStateRepository;
  @Autowired EvaluationService evaluationService;

  private Project project;
  private Environment environment;

  @BeforeEach
  void setUp() {
    Organization org =
        organizationRepository.save(
            Organization.builder().name("Acme").slug("acme-" + UUID.randomUUID()).build());
    project = projectRepository.save(Project.builder().organization(org).name("web").build());
    environment =
        environmentRepository.save(
            Environment.builder()
                .project(project)
                .name("production")
                .apiKeyHash(ApiKeyHasher.hash(UUID.randomUUID().toString()))
                .build());
  }

  private FeatureFlag flag(String key, LocalDateTime expiresAt) {
    return featureFlagRepository.save(
        FeatureFlag.builder()
            .project(project)
            .name(key)
            .key(key)
            .valueType(FlagValueType.BOOLEAN)
            .archived(false)
            .expiresAt(expiresAt)
            .build());
  }

  private FlagEnvironmentState state(FeatureFlag flag) {
    return flagStateRepository.save(
        FlagEnvironmentState.builder()
            .featureFlag(flag)
            .environment(environment)
            .enabled(true)
            .rolloutPercent(100)
            .build());
  }

  /**
   * Force-sets {@code last_evaluated_at} to an arbitrary value by passing a far-future threshold,
   * so the guard always matches. Reuses the production query rather than raw SQL.
   */
  private void forceLastEvaluatedAt(FeatureFlag flag, LocalDateTime value) {
    flagStateRepository.touchLastEvaluatedAt(
        flag.getId(), environment.getId(), value, LocalDateTime.now().plusYears(1));
  }

  @Test
  @DisplayName("an SDK evaluation persists last_evaluated_at despite the read-only transaction")
  void evaluationPersistsLastEvaluatedAt() {
    FeatureFlag flag = flag("checkout-v2", null);
    FlagEnvironmentState created = state(flag);
    assertThat(created.getLastEvaluatedAt()).isNull();

    evaluationService.getAllFlags(environment, null);

    FlagEnvironmentState reloaded = flagStateRepository.findById(created.getId()).orElseThrow();
    assertThat(reloaded.getLastEvaluatedAt())
        .as("REQUIRES_NEW must give the usage write its own writable transaction")
        .isNotNull();
  }

  @Test
  @DisplayName("evaluating one flag stamps only that flag's state")
  void singleFlagEvaluationStampsOnlyThatFlag() {
    FeatureFlag evaluated = flag("evaluated-flag", null);
    FeatureFlag untouched = flag("untouched-flag", null);
    FlagEnvironmentState evaluatedState = state(evaluated);
    FlagEnvironmentState untouchedState = state(untouched);

    evaluationService.getFlag(environment, "evaluated-flag", null);

    assertThat(
            flagStateRepository.findById(evaluatedState.getId()).orElseThrow().getLastEvaluatedAt())
        .isNotNull();
    assertThat(
            flagStateRepository.findById(untouchedState.getId()).orElseThrow().getLastEvaluatedAt())
        .isNull();
  }

  @Test
  @DisplayName("the usage write does not touch updated_at, which means 'config last changed'")
  void usageWriteDoesNotBumpUpdatedAt() {
    FeatureFlag flag = flag("audit-safe", null);
    FlagEnvironmentState created = state(flag);
    LocalDateTime updatedAtBefore = created.getUpdatedAt();

    forceLastEvaluatedAt(flag, LocalDateTime.now());

    FlagEnvironmentState reloaded = flagStateRepository.findById(created.getId()).orElseThrow();
    assertThat(reloaded.getLastEvaluatedAt()).isNotNull();
    assertThat(reloaded.getUpdatedAt()).isEqualTo(updatedAtBefore);
  }

  // --- report queries -------------------------------------------------------------------------

  @Test
  void staleQueryReturnsOnlyPairsNotEvaluatedSinceTheCutoff() {
    FeatureFlag stale = flag("stale-flag", null);
    FeatureFlag fresh = flag("fresh-flag", null);
    state(stale);
    state(fresh);
    forceLastEvaluatedAt(stale, LocalDateTime.now().minusDays(2));
    forceLastEvaluatedAt(fresh, LocalDateTime.now());

    List<FlagEnvironmentState> rows =
        flagStateRepository
            .findStaleHygieneRows(
                project.getId(), LocalDateTime.now().minusHours(1), PageRequest.of(0, 20))
            .getContent();

    assertThat(rows).extracting(s -> s.getFeatureFlag().getKey()).containsExactly("stale-flag");
  }

  /**
   * A flag created moments ago has never been evaluated, but calling it stale would flag every new
   * flag on sight — noise, not signal.
   */
  @Test
  @DisplayName("a newly created, never-evaluated flag is not reported stale")
  void newNeverEvaluatedFlagIsNotStale() {
    state(flag("brand-new", null));

    List<FlagEnvironmentState> rows =
        flagStateRepository
            .findStaleHygieneRows(
                project.getId(), LocalDateTime.now().minusHours(1), PageRequest.of(0, 20))
            .getContent();

    assertThat(rows).isEmpty();
  }

  @Test
  void expiredQueryReturnsOnlyFlagsPastTheirExpiry() {
    state(flag("expired-flag", LocalDateTime.now().minusDays(1)));
    state(flag("future-flag", LocalDateTime.now().plusDays(30)));
    state(flag("no-expiry-flag", null));

    List<FlagEnvironmentState> rows =
        flagStateRepository
            .findExpiredHygieneRows(project.getId(), LocalDateTime.now(), PageRequest.of(0, 20))
            .getContent();

    assertThat(rows).extracting(s -> s.getFeatureFlag().getKey()).containsExactly("expired-flag");
  }

  @Test
  void allQueryExcludesArchivedFlags() {
    state(flag("active-flag", null));
    FeatureFlag archived = flag("archived-flag", null);
    state(archived);
    archived.setArchived(true);
    featureFlagRepository.save(archived);

    List<FlagEnvironmentState> rows =
        flagStateRepository.findHygieneRows(project.getId(), PageRequest.of(0, 20)).getContent();

    assertThat(rows).extracting(s -> s.getFeatureFlag().getKey()).containsExactly("active-flag");
  }

  @Test
  @DisplayName("the report is scoped to its project")
  void reportIsScopedToProject() {
    state(flag("mine", null));

    Organization otherOrg =
        organizationRepository.save(
            Organization.builder().name("Other").slug("other-" + UUID.randomUUID()).build());
    Project otherProject =
        projectRepository.save(Project.builder().organization(otherOrg).name("other").build());
    Environment otherEnv =
        environmentRepository.save(
            Environment.builder()
                .project(otherProject)
                .name("production")
                .apiKeyHash(ApiKeyHasher.hash(UUID.randomUUID().toString()))
                .build());
    FeatureFlag theirs =
        featureFlagRepository.save(
            FeatureFlag.builder()
                .project(otherProject)
                .name("theirs")
                .key("theirs")
                .valueType(FlagValueType.BOOLEAN)
                .archived(false)
                .build());
    flagStateRepository.save(
        FlagEnvironmentState.builder()
            .featureFlag(theirs)
            .environment(otherEnv)
            .enabled(true)
            .rolloutPercent(100)
            .build());

    List<FlagEnvironmentState> rows =
        flagStateRepository.findHygieneRows(project.getId(), PageRequest.of(0, 20)).getContent();

    assertThat(rows).extracting(s -> s.getFeatureFlag().getKey()).containsExactly("mine");
  }
}
