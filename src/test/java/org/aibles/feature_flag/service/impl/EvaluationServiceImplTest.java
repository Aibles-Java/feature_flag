package org.aibles.feature_flag.service.impl;

import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.domain.entity.FeatureFlag;
import org.aibles.feature_flag.domain.entity.FlagEnvironmentState;
import org.aibles.feature_flag.domain.entity.Project;
import org.aibles.feature_flag.domain.enums.FlagValueType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.aibles.feature_flag.dto.response.FlagEvaluationResponse;
import org.aibles.feature_flag.exception.ResourceNotFoundException;
import org.aibles.feature_flag.metrics.FeatureFlagMetrics;
import org.aibles.feature_flag.repository.FeatureFlagRepository;
import org.aibles.feature_flag.repository.FlagEnvironmentStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EvaluationServiceImplTest {

    @Mock FlagEnvironmentStateRepository flagStateRepository;
    @Mock FeatureFlagRepository featureFlagRepository;

    EvaluationServiceImpl service;

    UUID projectId = UUID.randomUUID();
    UUID envId = UUID.randomUUID();
    Project project;
    Environment environment;

    @BeforeEach
    void setUp() {
        service = new EvaluationServiceImpl(flagStateRepository, featureFlagRepository,
                new FeatureFlagMetrics(new SimpleMeterRegistry()));
        project = Project.builder().id(projectId).name("proj").build();
        environment = Environment.builder().id(envId).project(project).name("prod").apiKeyHash("key").build();
    }

    @Test
    void getAllFlags_returnsMappedResponsesFromRepository() {
        FeatureFlag flag = FeatureFlag.builder()
                .id(UUID.randomUUID()).project(project).name("Beta").key("beta-feature")
                .valueType(FlagValueType.BOOLEAN).archived(false).build();
        FlagEnvironmentState state = FlagEnvironmentState.builder()
                .featureFlag(flag).environment(environment)
                .enabled(true).rolloutPercent(100).build();

        when(flagStateRepository.findAllActiveByEnvironmentId(envId)).thenReturn(List.of(state));

        List<FlagEvaluationResponse> result = service.getAllFlags(environment, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getFlagKey()).isEqualTo("beta-feature");
        assertThat(result.get(0).isEnabled()).isTrue();
    }

    @Test
    void getAllFlags_returnsEmptyList_whenNoActiveFlags() {
        when(flagStateRepository.findAllActiveByEnvironmentId(envId)).thenReturn(List.of());

        List<FlagEvaluationResponse> result = service.getAllFlags(environment, "user-1");

        assertThat(result).isEmpty();
    }

    @Test
    void getFlag_throwsResourceNotFound_whenFlagIsArchived() {
        FeatureFlag archived = FeatureFlag.builder()
                .id(UUID.randomUUID()).project(project).name("Old").key("old-flag")
                .valueType(FlagValueType.BOOLEAN).archived(true).build();

        when(featureFlagRepository.findByProjectIdAndKey(projectId, "old-flag"))
                .thenReturn(Optional.of(archived));

        assertThatThrownBy(() -> service.getFlag(environment, "old-flag", null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getFlag_throwsResourceNotFound_whenFlagDoesNotExist() {
        when(featureFlagRepository.findByProjectIdAndKey(projectId, "unknown"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getFlag(environment, "unknown", null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getFlag_returnsEvaluation_whenFlagIsActive() {
        UUID flagId = UUID.randomUUID();
        FeatureFlag flag = FeatureFlag.builder()
                .id(flagId).project(project).name("Feature").key("active-flag")
                .valueType(FlagValueType.BOOLEAN).archived(false).build();
        FlagEnvironmentState state = FlagEnvironmentState.builder()
                .featureFlag(flag).environment(environment)
                .enabled(true).value("true").rolloutPercent(100).build();

        when(featureFlagRepository.findByProjectIdAndKey(projectId, "active-flag"))
                .thenReturn(Optional.of(flag));
        when(flagStateRepository.findByFeatureFlagIdAndEnvironmentId(flagId, envId))
                .thenReturn(Optional.of(state));

        FlagEvaluationResponse response = service.getFlag(environment, "active-flag", null);

        assertThat(response.getFlagKey()).isEqualTo("active-flag");
        assertThat(response.isEnabled()).isTrue();
    }

    @Test
    void getFlag_returnsDisabled_whenRolloutIsZero() {
        UUID flagId = UUID.randomUUID();
        FeatureFlag flag = FeatureFlag.builder()
                .id(flagId).project(project).name("Rollout").key("rollout-flag")
                .valueType(FlagValueType.BOOLEAN).archived(false).build();
        FlagEnvironmentState state = FlagEnvironmentState.builder()
                .featureFlag(flag).environment(environment)
                .enabled(true).rolloutPercent(0).build();

        when(featureFlagRepository.findByProjectIdAndKey(projectId, "rollout-flag"))
                .thenReturn(Optional.of(flag));
        when(flagStateRepository.findByFeatureFlagIdAndEnvironmentId(flagId, envId))
                .thenReturn(Optional.of(state));

        FlagEvaluationResponse response = service.getFlag(environment, "rollout-flag", "user-123");

        assertThat(response.isEnabled()).isFalse();
        assertThat(response.getValue()).isNull();
    }
}
