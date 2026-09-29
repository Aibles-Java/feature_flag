package org.aibles.feature_flag.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.domain.entity.FeatureFlag;
import org.aibles.feature_flag.domain.entity.FlagEnvironmentState;
import org.aibles.feature_flag.domain.entity.Project;
import org.aibles.feature_flag.domain.enums.FlagValueType;
import org.aibles.feature_flag.dto.response.FlagEvaluationResponse;
import org.aibles.feature_flag.exception.ResourceNotFoundException;
import org.aibles.feature_flag.hygiene.FlagEvaluationTracker;
import org.aibles.feature_flag.metrics.FeatureFlagMetrics;
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
  @Mock FlagEvaluationTracker evaluationTracker;

  EvaluationServiceImpl service;

  UUID projectId = UUID.randomUUID();
  UUID envId = UUID.randomUUID();
  Project project;
  Environment environment;

  @BeforeEach
  void setUp() {
    service =
        new EvaluationServiceImpl(
            flagStateRepository,
            evaluationCacheService,
            new FeatureFlagMetrics(new SimpleMeterRegistry()),
            evaluationTracker);
    project = Project.builder().id(projectId).name("proj").build();
    environment = Environment.builder().id(envId).project(project).name("prod").build();
  }

  /** Simulates cache hit: getOrLoad returns the provided list, loader never invoked. */
  private void cacheHit(UUID id, List<FlagStateSnapshot> snapshots) {
    when(evaluationCacheService.getOrLoad(eq(id), any())).thenReturn(snapshots);
  }

  /** Simulates cache miss: getOrLoad delegates to the loader function (which calls the repo). */
  @SuppressWarnings("unchecked")
  private void cacheMiss(UUID id) {
    when(evaluationCacheService.getOrLoad(eq(id), any()))
        .thenAnswer(
            inv -> ((Function<UUID, List<FlagStateSnapshot>>) inv.getArgument(1)).apply(id));
  }

  @Test
  void getAllFlags_hitsCacheFirst_andSkipsRepository() {
    FlagStateSnapshot snapshot =
        new FlagStateSnapshot(
            UUID.randomUUID(), "beta-feature", true, null, FlagValueType.BOOLEAN, 100);
    cacheHit(envId, List.of(snapshot));

    List<FlagEvaluationResponse> result = service.getAllFlags(environment, null);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).getFlagKey()).isEqualTo("beta-feature");
    verify(flagStateRepository, never()).findAllActiveByEnvironmentId(envId);
  }

  @Test
  void getAllFlags_loadsFromRepoOnCacheMiss() {
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

    cacheMiss(envId);
    when(flagStateRepository.findAllActiveByEnvironmentId(envId)).thenReturn(List.of(state));

    List<FlagEvaluationResponse> result = service.getAllFlags(environment, null);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).getFlagKey()).isEqualTo("beta-feature");
    assertThat(result.get(0).isEnabled()).isTrue();
    verify(flagStateRepository).findAllActiveByEnvironmentId(envId);
  }

  @Test
  void getAllFlags_returnsEmptyList_whenNoActiveFlags() {
    cacheMiss(envId);
    when(flagStateRepository.findAllActiveByEnvironmentId(envId)).thenReturn(List.of());

    List<FlagEvaluationResponse> result = service.getAllFlags(environment, "user-1");

    assertThat(result).isEmpty();
  }

  @Test
  void getFlag_returnsFlagFromCache_whenCacheIsWarm() {
    FlagStateSnapshot snapshot =
        new FlagStateSnapshot(
            UUID.randomUUID(), "active-flag", true, "true", FlagValueType.BOOLEAN, 100);
    cacheHit(envId, List.of(snapshot));

    FlagEvaluationResponse response = service.getFlag(environment, "active-flag", null);

    assertThat(response.getFlagKey()).isEqualTo("active-flag");
    assertThat(response.isEnabled()).isTrue();
    verify(flagStateRepository, never()).findAllActiveByEnvironmentId(envId);
  }

  @Test
  void getFlag_throwsResourceNotFound_whenFlagNotInActiveSnapshots() {
    cacheHit(envId, List.of());

    assertThatThrownBy(() -> service.getFlag(environment, "missing-flag", null))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  void getFlag_throwsResourceNotFound_whenArchivedFlagNotInActiveSnapshots() {
    cacheMiss(envId);
    when(flagStateRepository.findAllActiveByEnvironmentId(envId)).thenReturn(List.of());

    assertThatThrownBy(() -> service.getFlag(environment, "archived-flag", null))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  void getFlag_returnsDisabled_whenRolloutIsZero() {
    FlagStateSnapshot snapshot =
        new FlagStateSnapshot(
            UUID.randomUUID(), "rollout-flag", true, null, FlagValueType.BOOLEAN, 0);
    cacheHit(envId, List.of(snapshot));

    FlagEvaluationResponse response = service.getFlag(environment, "rollout-flag", "user-123");

    assertThat(response.isEnabled()).isFalse();
    assertThat(response.getValue()).isNull();
  }

  /**
   * ADR-0004 decision 2: with no identifier there is nothing to bucket, so a partial rollout fails
   * OPEN. This keeps the response identical to the pre-rollout contract for clients that never send
   * an identifier — and is why a rollout percentage is not access control.
   */
  @Test
  void getFlag_failsOpen_whenPartialRolloutAndNoIdentifier() {
    FlagEnvironmentState state = partialRolloutState(1);

    FlagEvaluationResponse response = service.getFlag(environment, "rollout-flag", null);

    assertThat(response.isEnabled()).isTrue();
    assertThat(response.getValue()).isEqualTo("on");
    assertThat(response.getRolloutPercent()).isEqualTo(1);
    assertThat(state.getRolloutPercent()).as("configured state is untouched").isEqualTo(1);
  }

  @Test
  void getFlag_failsOpen_whenPartialRolloutAndBlankIdentifier() {
    partialRolloutState(1);

    assertThat(service.getFlag(environment, "rollout-flag", "   ").isEnabled()).isTrue();
  }

  /**
   * The rollout must not override an explicitly disabled flag: a caller inside the bucket still
   * sees it off.
   */
  @Test
  void getFlag_staysOff_whenDisabledEvenAtFullRollout() {
    UUID flagId = UUID.randomUUID();
    FeatureFlag flag = rolloutFlag(flagId);
    FlagEnvironmentState state =
        FlagEnvironmentState.builder()
            .featureFlag(flag)
            .environment(environment)
            .enabled(false)
            .value("on")
            .rolloutPercent(100)
            .build();
    stubLookup(flagId, flag, state);

    FlagEvaluationResponse response = service.getFlag(environment, "rollout-flag", "user-123");

    assertThat(response.isEnabled()).isFalse();
    assertThat(response.getValue()).isNull();
  }

  /** The response always reports the configured percentage, whatever this caller resolved to. */
  @Test
  void getFlag_reportsConfiguredRolloutPercent_regardlessOfOutcome() {
    partialRolloutState(50);

    FlagEvaluationResponse response = service.getFlag(environment, "rollout-flag", "user-123");

    assertThat(response.getRolloutPercent()).isEqualTo(50);
  }

  /** Same identifier, repeated calls — the service must not introduce any per-call variation. */
  @Test
  void getFlag_isDeterministicForTheSameIdentifier() {
    partialRolloutState(50);

    boolean first = service.getFlag(environment, "rollout-flag", "user-123").isEnabled();
    for (int i = 0; i < 5; i++) {
      assertThat(service.getFlag(environment, "rollout-flag", "user-123").isEnabled())
          .isEqualTo(first);
    }
  }

  /**
   * Rollout now runs on top of a cached snapshot rather than a fresh entity read, so these tests
   * stub the cache. Stubbing the repositories would exercise a path the SDK no longer takes on a
   * warm cache, which is the common case.
   */
  private void stubLookup(UUID flagId, FeatureFlag flag, FlagEnvironmentState state) {
    cacheHit(
        envId,
        List.of(
            new FlagStateSnapshot(
                flagId,
                flag.getKey(),
                state.isEnabled(),
                state.getValue(),
                flag.getValueType(),
                state.getRolloutPercent())));
  }

  private FeatureFlag rolloutFlag(UUID flagId) {
    return FeatureFlag.builder()
        .id(flagId)
        .project(project)
        .name("Rollout")
        .key("rollout-flag")
        .valueType(FlagValueType.BOOLEAN)
        .archived(false)
        .build();
  }

  /** Stubs an enabled `rollout-flag` at the given percentage and returns its state. */
  private FlagEnvironmentState partialRolloutState(int rolloutPercent) {
    UUID flagId = UUID.randomUUID();
    FeatureFlag flag = rolloutFlag(flagId);
    FlagEnvironmentState state =
        FlagEnvironmentState.builder()
            .featureFlag(flag)
            .environment(environment)
            .enabled(true)
            .value("on")
            .rolloutPercent(rolloutPercent)
            .build();
    stubLookup(flagId, flag, state);
    return state;
  }
}
