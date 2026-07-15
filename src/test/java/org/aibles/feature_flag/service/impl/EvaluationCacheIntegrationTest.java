package org.aibles.feature_flag.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.UUID;
import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.domain.entity.FeatureFlag;
import org.aibles.feature_flag.domain.entity.FlagEnvironmentState;
import org.aibles.feature_flag.domain.entity.Organization;
import org.aibles.feature_flag.domain.entity.Project;
import org.aibles.feature_flag.domain.enums.FlagValueType;
import org.aibles.feature_flag.domain.enums.MemberRole;
import org.aibles.feature_flag.dto.request.UpdateFlagStateRequest;
import org.aibles.feature_flag.dto.response.FlagEvaluationResponse;
import org.aibles.feature_flag.repository.EnvironmentRepository;
import org.aibles.feature_flag.repository.FeatureFlagRepository;
import org.aibles.feature_flag.repository.FlagEnvironmentStateRepository;
import org.aibles.feature_flag.repository.OrganizationRepository;
import org.aibles.feature_flag.repository.ProjectRepository;
import org.aibles.feature_flag.service.EvaluationCacheService;
import org.aibles.feature_flag.service.EvaluationService;
import org.aibles.feature_flag.service.FeatureFlagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Verifies that the Caffeine evaluation cache eliminates repeated DB queries and that admin
 * mutations invalidate the cache so fresh data is fetched on the next SDK call.
 *
 * <p>Uses a separate H2 DB to avoid Liquibase collision with the shared testdb context.
 *
 * <p>Not annotated with @Transactional so that featureFlagService.updateState() commits its own
 * transaction and the afterCommit eviction hook fires during the test.
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:cache-testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;"
          + "MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=KEY,VALUE;"
          + "CASE_INSENSITIVE_IDENTIFIERS=TRUE",
    })
@ActiveProfiles("test")
class EvaluationCacheIntegrationTest {

  @Autowired EvaluationService evaluationService;
  @Autowired EvaluationCacheService evaluationCacheService;
  @Autowired FeatureFlagService featureFlagService;

  @MockitoSpyBean FlagEnvironmentStateRepository flagStateRepository;

  // Stub out permission checks so featureFlagService.updateState() can be called without a
  // real SecurityContextHolder / OrganizationMember setup.
  @MockitoBean PermissionService permissionService;

  @Autowired OrganizationRepository organizationRepository;
  @Autowired ProjectRepository projectRepository;
  @Autowired EnvironmentRepository environmentRepository;
  @Autowired FeatureFlagRepository featureFlagRepository;

  Environment env;
  FeatureFlag flag;

  @BeforeEach
  void setUp() {
    // Permission stub: allow any role check
    org.mockito.Mockito.doNothing()
        .when(permissionService)
        .requireRoleForProject(ArgumentMatchers.any(), ArgumentMatchers.<MemberRole[]>any());

    String suffix = UUID.randomUUID().toString().substring(0, 8);
    Organization org =
        organizationRepository.save(
            Organization.builder().name("TestOrg-" + suffix).slug("test-org-" + suffix).build());
    Project project =
        projectRepository.save(Project.builder().organization(org).name("TestProj").build());
    env =
        environmentRepository.save(
            Environment.builder()
                .project(project)
                .name("prod")
                .apiKeyHash("hash-" + UUID.randomUUID())
                .build());
    flag =
        featureFlagRepository.save(
            FeatureFlag.builder()
                .project(project)
                .name("Dark Mode")
                .key("dark-mode")
                .valueType(FlagValueType.BOOLEAN)
                .build());
    flagStateRepository.save(
        FlagEnvironmentState.builder()
            .featureFlag(flag)
            .environment(env)
            .enabled(true)
            .rolloutPercent(100)
            .build());

    // Start each test with a cold cache
    evaluationCacheService.evict(env.getId());
  }

  @Test
  void repeatedGetAllFlags_hitsDbOnlyOnce() {
    evaluationService.getAllFlags(env, null);
    evaluationService.getAllFlags(env, null);
    evaluationService.getAllFlags(env, null);

    verify(flagStateRepository, times(1)).findAllActiveByEnvironmentId(env.getId());
  }

  @Test
  void getAllFlags_returnsFlagData() {
    List<FlagEvaluationResponse> flags = evaluationService.getAllFlags(env, null);

    assertThat(flags).hasSize(1);
    assertThat(flags.get(0).getFlagKey()).isEqualTo("dark-mode");
    assertThat(flags.get(0).isEnabled()).isTrue();
  }

  @Test
  void updateState_viaRealService_invalidatesCache_soNextCallHitsDb() {
    // Warm the cache
    evaluationService.getAllFlags(env, null);

    // Mutate via the real FeatureFlagService — its @Transactional commits, triggering
    // evictAfterCommit so the cache entry is dropped before the next SDK call.
    UpdateFlagStateRequest req = new UpdateFlagStateRequest();
    req.setEnabled(false);
    req.setValue("false");
    featureFlagService.updateState(flag.getId(), env.getId(), req);

    // Second getAllFlags should hit the DB again (cache was evicted after commit)
    evaluationService.getAllFlags(env, null);

    verify(flagStateRepository, times(2)).findAllActiveByEnvironmentId(env.getId());
  }

  @Test
  void getFlag_usesCache_afterGetAllFlagsWarmsIt() {
    evaluationService.getAllFlags(env, null);
    evaluationService.getFlag(env, "dark-mode", null);

    // Both calls share the same cache — only one DB hit total
    verify(flagStateRepository, times(1)).findAllActiveByEnvironmentId(env.getId());
  }
}
