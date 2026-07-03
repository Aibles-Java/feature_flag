package org.aibles.feature_flag.service.impl;

import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.domain.entity.FeatureFlag;
import org.aibles.feature_flag.domain.entity.FlagEnvironmentState;
import org.aibles.feature_flag.domain.entity.Project;
import org.aibles.feature_flag.domain.enums.FlagValueType;
import org.aibles.feature_flag.dto.response.FlagEvaluationResponse;
import org.aibles.feature_flag.exception.ResourceNotFoundException;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EvaluationServiceImplTest {

    @Mock
    private FlagEnvironmentStateRepository flagStateRepository;
    @Mock
    private FeatureFlagRepository featureFlagRepository;

    private EvaluationServiceImpl evaluationService;

    private final UUID envId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private Environment environment;

    @BeforeEach
    void setUp() {
        evaluationService = new EvaluationServiceImpl(flagStateRepository, featureFlagRepository);
        Project project = Project.builder().id(projectId).build();
        environment = Environment.builder().id(envId).project(project).build();
    }

    private FlagEnvironmentState state(String key, boolean enabled, String value, int rollout) {
        FeatureFlag flag = FeatureFlag.builder()
                .id(UUID.randomUUID())
                .key(key)
                .valueType(FlagValueType.STRING)
                .build();
        return FlagEnvironmentState.builder()
                .featureFlag(flag)
                .environment(environment)
                .enabled(enabled)
                .value(value)
                .rolloutPercent(rollout)
                .build();
    }

    @Test
    void getAllFlagsMapsActiveStatesFromRepository() {

        when(flagStateRepository.findAllActiveByEnvironmentId(envId))
                .thenReturn(List.of(
                        state("flag-on", true, "hello", 100),
                        state("flag-off", false, "ignored", 100)));

        List<FlagEvaluationResponse> result = evaluationService.getAllFlags(environment, null);

        verify(flagStateRepository).findAllActiveByEnvironmentId(envId);
        assertThat(result).hasSize(2);

        FlagEvaluationResponse on = result.get(0);
        assertThat(on.getFlagKey()).isEqualTo("flag-on");
        assertThat(on.isEnabled()).isTrue();
        assertThat(on.getValue()).isEqualTo("hello");
        assertThat(on.getValueType()).isEqualTo(FlagValueType.STRING);

        FlagEvaluationResponse off = result.get(1);
        assertThat(off.isEnabled()).isFalse();
        assertThat(off.getValue()).isNull();
    }

    @Test
    void getAllFlagsReturnsEmptyWhenNoActiveStates() {
        when(flagStateRepository.findAllActiveByEnvironmentId(envId)).thenReturn(List.of());

        assertThat(evaluationService.getAllFlags(environment, "user-1")).isEmpty();
    }

    @Test
    void getFlagReturnsEvaluationForActiveFlag() {
        FeatureFlag flag = FeatureFlag.builder()
                .id(UUID.randomUUID())
                .key("flag-on")
                .valueType(FlagValueType.STRING)
                .archived(false)
                .build();
        when(featureFlagRepository.findByProjectIdAndKey(projectId, "flag-on"))
                .thenReturn(Optional.of(flag));
        FlagEnvironmentState st = FlagEnvironmentState.builder()
                .featureFlag(flag).environment(environment).enabled(true).value("v").rolloutPercent(100).build();
        when(flagStateRepository.findByFeatureFlagIdAndEnvironmentId(flag.getId(), envId))
                .thenReturn(Optional.of(st));

        FlagEvaluationResponse response = evaluationService.getFlag(environment, "flag-on", null);

        assertThat(response.getFlagKey()).isEqualTo("flag-on");
        assertThat(response.isEnabled()).isTrue();
        assertThat(response.getValue()).isEqualTo("v");
    }

    @Test
    void getFlagThrowsWhenFlagKeyUnknown() {
        when(featureFlagRepository.findByProjectIdAndKey(projectId, "missing"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> evaluationService.getFlag(environment, "missing", null))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void getFlagTreatsArchivedFlagAsNotFound() {

        FeatureFlag archived = FeatureFlag.builder()
                .id(UUID.randomUUID())
                .key("legacy")
                .valueType(FlagValueType.STRING)
                .archived(true)
                .build();
        when(featureFlagRepository.findByProjectIdAndKey(projectId, "legacy"))
                .thenReturn(Optional.of(archived));

        assertThatThrownBy(() -> evaluationService.getFlag(environment, "legacy", null))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("legacy");
    }

    @Test
    void getFlagThrowsWhenStateMissingForEnvironment() {
        FeatureFlag flag = FeatureFlag.builder()
                .id(UUID.randomUUID())
                .key("flag-on")
                .valueType(FlagValueType.STRING)
                .archived(false)
                .build();
        when(featureFlagRepository.findByProjectIdAndKey(projectId, "flag-on"))
                .thenReturn(Optional.of(flag));
        when(flagStateRepository.findByFeatureFlagIdAndEnvironmentId(flag.getId(), envId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> evaluationService.getFlag(environment, "flag-on", null))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Flag state not found");
    }
}
