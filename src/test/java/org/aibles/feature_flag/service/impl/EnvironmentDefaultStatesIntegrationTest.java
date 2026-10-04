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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

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
  @Autowired FlagEnvironmentStateRepository stateRepository;

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
    // Env created BEFORE the flag, and the pair row deleted: simulates a missing cell.
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
}
