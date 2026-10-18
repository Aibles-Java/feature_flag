package org.aibles.feature_flag.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.domain.entity.FeatureFlag;
import org.aibles.feature_flag.domain.entity.FlagEnvironmentState;
import org.aibles.feature_flag.domain.entity.Organization;
import org.aibles.feature_flag.domain.entity.Project;
import org.aibles.feature_flag.domain.enums.EnvType;
import org.aibles.feature_flag.domain.enums.FlagValueType;
import org.aibles.feature_flag.dto.request.CreateEnvironmentRequest;
import org.aibles.feature_flag.dto.request.UpdateFlagStateRequest;
import org.aibles.feature_flag.dto.response.EnvironmentSecretResponse;
import org.aibles.feature_flag.exception.ResourceNotFoundException;
import org.aibles.feature_flag.repository.EnvironmentRepository;
import org.aibles.feature_flag.repository.FeatureFlagRepository;
import org.aibles.feature_flag.repository.FlagEnvironmentStateRepository;
import org.aibles.feature_flag.repository.OrganizationRepository;
import org.aibles.feature_flag.repository.ProjectRepository;
import org.aibles.feature_flag.service.EnvironmentService;
import org.aibles.feature_flag.service.FeatureFlagService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * S-2.8 (ADR-03, F11, T-F11-1) against the real Liquibase schema on H2.
 *
 * <p>H2 in PostgreSQL mode is not PostgreSQL: the backlog requires confirming the transactional
 * behaviour on real PostgreSQL before G2.
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:envstates-testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;"
          + "MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=KEY,VALUE;"
          + "CASE_INSENSITIVE_IDENTIFIERS=TRUE",
    })
@ActiveProfiles("test")
class EnvironmentDefaultStatesIntegrationTest {

  @Autowired EnvironmentService environmentService;
  @Autowired FeatureFlagService featureFlagService;
  @Autowired OrganizationRepository organizationRepository;
  @Autowired ProjectRepository projectRepository;
  @Autowired EnvironmentRepository environmentRepository;
  @Autowired FeatureFlagRepository featureFlagRepository;
  @MockitoSpyBean FlagEnvironmentStateRepository stateRepository;

  // The mock allows every action; ABAC itself is covered elsewhere.
  @MockitoBean PermissionService permissionService;

  private Project newProject() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    Organization org =
        organizationRepository.save(
            Organization.builder().name("Org-" + suffix).slug("org-" + suffix).build());
    return projectRepository.save(Project.builder().organization(org).name("Proj").build());
  }

  private void seedFlags(Project project, int n) {
    List<FeatureFlag> flags =
        IntStream.range(0, n)
            .mapToObj(
                i ->
                    FeatureFlag.builder()
                        .project(project)
                        .name("F" + i)
                        .key("synthetic-flag-" + i)
                        .valueType(FlagValueType.BOOLEAN)
                        .archived(i % 10 == 0)
                        .build())
            .toList();
    featureFlagRepository.saveAll(flags);
  }

  private EnvironmentSecretResponse createEnv(Project project, String name) {
    CreateEnvironmentRequest req = new CreateEnvironmentRequest();
    req.setProjectId(project.getId());
    req.setName(name);
    return environmentService.create(req);
  }

  @Test
  void createEnvironment_createsExactlyNDefaultStates_forNFlags() {
    Project project = newProject();
    seedFlags(project, 7);

    EnvironmentSecretResponse env = createEnv(project, "staging");

    List<FlagEnvironmentState> states =
        stateRepository.findAll().stream()
            .filter(s -> s.getEnvironment().getId().equals(env.getId()))
            .toList();
    assertThat(states).hasSize(7);
    assertThat(states)
        .allSatisfy(
            s -> {
              assertThat(s.isEnabled()).isFalse();
              assertThat(s.getRolloutPercent()).isEqualTo(100);
              assertThat(s.getVersion()).isZero();
              assertThat(s.getValue()).isNull();
            });
  }

  @Test
  void createEnvironment_handlesA1000FlagProject_inOneCall() {
    Project project = newProject();
    seedFlags(project, 1000);

    EnvironmentSecretResponse env = createEnv(project, "prod");

    assertThat(
            stateRepository.findAll().stream()
                .filter(s -> s.getEnvironment().getId().equals(env.getId()))
                .count())
        .isEqualTo(1000);
  }

  @Test
  void updateState_forPairWithoutState_is404_andDoesNotLazyCreate() {
    Project project = newProject();
    // Env created BEFORE the flag, so the (flag, env) pair has no state row: a missing cell.
    Environment env =
        environmentRepository.save(Environment.builder().project(project).name("qa").build());
    FeatureFlag flag =
        featureFlagRepository.save(
            FeatureFlag.builder()
                .project(project)
                .name("F")
                .key("orphan-flag")
                .valueType(FlagValueType.BOOLEAN)
                .build());
    long before = stateRepository.count();

    UpdateFlagStateRequest req = new UpdateFlagStateRequest();
    req.setEnabled(true);
    assertThatThrownBy(() -> featureFlagService.updateState(flag.getId(), env.getId(), req))
        .isInstanceOf(ResourceNotFoundException.class);

    assertThat(stateRepository.count()).isEqualTo(before);
    assertThat(stateRepository.findByFeatureFlagIdAndEnvironmentId(flag.getId(), env.getId()))
        .isEmpty();
  }

  @Test
  void createProductionEnvironment_startsEveryStateDisabled_atFullRollout() {
    Project project = newProject();
    seedFlags(project, 5);
    CreateEnvironmentRequest req = new CreateEnvironmentRequest();
    req.setProjectId(project.getId());
    req.setName("prod");
    req.setType(EnvType.PRODUCTION);

    EnvironmentSecretResponse env = environmentService.create(req);

    assertThat(environmentRepository.findById(env.getId()).orElseThrow().getType())
        .isEqualTo(EnvType.PRODUCTION);
    List<FlagEnvironmentState> states =
        stateRepository.findAll().stream()
            .filter(s -> s.getEnvironment().getId().equals(env.getId()))
            .toList();
    assertThat(states).hasSize(5);
    assertThat(states)
        .allSatisfy(
            s -> {
              assertThat(s.isEnabled()).isFalse();
              assertThat(s.getRolloutPercent()).isEqualTo(100);
              assertThat(s.getVersion()).isZero();
            });
  }

  @Test
  void createEnvironment_withZeroFlags_createsNoStates() {
    Project project = newProject();

    EnvironmentSecretResponse env = createEnv(project, "empty");

    assertThat(
            stateRepository.findAll().stream()
                .filter(s -> s.getEnvironment().getId().equals(env.getId()))
                .count())
        .isZero();
  }

  @Test
  void createEnvironment_ignoresOtherProjectsFlags() {
    Project mine = newProject();
    Project other = newProject();
    seedFlags(mine, 3);
    seedFlags(other, 4);

    EnvironmentSecretResponse env = createEnv(mine, "dev");

    List<FlagEnvironmentState> states =
        stateRepository.findAll().stream()
            .filter(s -> s.getEnvironment().getId().equals(env.getId()))
            .toList();
    assertThat(states).hasSize(3);
    List<UUID> mineIds =
        featureFlagRepository.findAllByProjectId(mine.getId()).stream()
            .map(FeatureFlag::getId)
            .toList();
    assertThat(states)
        .extracting(s -> s.getFeatureFlag().getId())
        .containsExactlyInAnyOrderElementsOf(mineIds);
  }

  @Test
  void createEnvironment_rollsBackEnvironment_whenStateSaveFails() {
    Project project = newProject();
    seedFlags(project, 3);
    Mockito.doThrow(new IllegalStateException("boom")).when(stateRepository).saveAll(Mockito.any());

    try {
      assertThatThrownBy(() -> createEnv(project, "doomed"))
          .isInstanceOf(IllegalStateException.class);
    } finally {
      Mockito.reset(stateRepository);
    }

    assertThat(environmentRepository.existsByProjectIdAndName(project.getId(), "doomed")).isFalse();
  }
}
