package org.aibles.feature_flag.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.domain.entity.FeatureFlag;
import org.aibles.feature_flag.domain.entity.FlagEnvironmentState;
import org.aibles.feature_flag.domain.entity.Project;
import org.aibles.feature_flag.domain.enums.FlagValueType;
import org.aibles.feature_flag.dto.response.FlagEvaluationResponse;
import org.aibles.feature_flag.exception.ResourceNotFoundException;
import org.aibles.feature_flag.repository.FlagEnvironmentStateRepository;
import org.aibles.feature_flag.service.EvaluationCacheService;
import org.aibles.feature_flag.service.FlagStateSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EvaluationServiceImplTest {

  @Mock FlagEnvironmentStateRepository flagStateRepository;
  @Mock EvaluationCacheService evaluationCacheService;

  EvaluationServiceImpl service;

  UUID projectId = UUID.randomUUID();
  UUID envId = UUID.randomUUID();
  Project project;
  Environment environment;

  @BeforeEach
  void setUp() {
    service = new EvaluationServiceImpl(flagStateRepository, evaluationCacheService);
    project = Project.builder().id(projectId).name("proj").build();
    environment =
        Environment.builder().id(envId).project(project).name("prod").apiKeyHash("key").build();
  }

  @Test
  void getAllFlags_hitsCacheFirst_andSkipsRepository() {
    FlagStateSnapshot snapshot =
        new FlagStateSnapshot("beta-feature", true, null, FlagValueType.BOOLEAN, 100);
    when(evaluationCacheService.get(envId)).thenReturn(Optional.of(List.of(snapshot)));

    List<FlagEvaluationResponse> result = service.getAllFlags(environment, null);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).getFlagKey()).isEqualTo("beta-feature");
    verify(flagStateRepository, never()).findAllActiveByEnvironmentId(envId);
  }

  @Test
  void getAllFlags_loadsFromRepoOnCacheMiss_andCachesResult() {
    FeatureFlag flag =
        FeatureFlag.builder()
            .id(UUID.randomUUID())
            .project(project)
            .name("Beta")
            .key("beta-feature")
            .valueType(FlagValueType.BOOLEAN)
            .archived(false)
            .build();
    FlagEnvironmentState state =
        FlagEnvironmentState.builder()
            .featureFlag(flag)
            .environment(environment)
            .enabled(true)
            .rolloutPercent(100)
            .build();

    when(evaluationCacheService.get(envId)).thenReturn(Optional.empty());
    when(flagStateRepository.findAllActiveByEnvironmentId(envId)).thenReturn(List.of(state));

    List<FlagEvaluationResponse> result = service.getAllFlags(environment, null);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).getFlagKey()).isEqualTo("beta-feature");
    assertThat(result.get(0).isEnabled()).isTrue();
    verify(evaluationCacheService)
        .put(
            envId,
            List.of(new FlagStateSnapshot("beta-feature", true, null, FlagValueType.BOOLEAN, 100)));
  }

  @Test
  void getAllFlags_returnsEmptyList_whenNoActiveFlags() {
    when(evaluationCacheService.get(envId)).thenReturn(Optional.empty());
    when(flagStateRepository.findAllActiveByEnvironmentId(envId)).thenReturn(List.of());

    List<FlagEvaluationResponse> result = service.getAllFlags(environment, "user-1");

    assertThat(result).isEmpty();
  }

  @Test
  void getFlag_returnsFlagFromCache_whenCacheIsWarm() {
    FlagStateSnapshot snapshot =
        new FlagStateSnapshot("active-flag", true, "true", FlagValueType.BOOLEAN, 100);
    when(evaluationCacheService.get(envId)).thenReturn(Optional.of(List.of(snapshot)));

    FlagEvaluationResponse response = service.getFlag(environment, "active-flag", null);

    assertThat(response.getFlagKey()).isEqualTo("active-flag");
    assertThat(response.isEnabled()).isTrue();
    verify(flagStateRepository, never()).findAllActiveByEnvironmentId(envId);
  }

  @Test
  void getFlag_throwsResourceNotFound_whenFlagNotInActiveSnapshots() {
    when(evaluationCacheService.get(envId)).thenReturn(Optional.of(List.of()));

    assertThatThrownBy(() -> service.getFlag(environment, "missing-flag", null))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  void getFlag_throwsResourceNotFound_whenArchivedFlagNotInActiveSnapshots() {
    // Archived flags are excluded by findAllActiveByEnvironmentId — they simply don't appear
    when(evaluationCacheService.get(envId)).thenReturn(Optional.empty());
    when(flagStateRepository.findAllActiveByEnvironmentId(envId)).thenReturn(List.of());

    assertThatThrownBy(() -> service.getFlag(environment, "archived-flag", null))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  void getFlag_returnsDisabled_whenRolloutIsZero() {
    FlagStateSnapshot snapshot =
        new FlagStateSnapshot("rollout-flag", true, null, FlagValueType.BOOLEAN, 0);
    when(evaluationCacheService.get(envId)).thenReturn(Optional.of(List.of(snapshot)));

    FlagEvaluationResponse response = service.getFlag(environment, "rollout-flag", "user-123");

    assertThat(response.isEnabled()).isFalse();
    assertThat(response.getValue()).isNull();
  }
}
