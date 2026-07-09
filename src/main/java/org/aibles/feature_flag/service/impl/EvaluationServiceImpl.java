package org.aibles.feature_flag.service.impl;

import lombok.RequiredArgsConstructor;
import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.domain.entity.FeatureFlag;
import org.aibles.feature_flag.domain.entity.FlagEnvironmentState;
import org.aibles.feature_flag.dto.response.FlagEvaluationResponse;
import org.aibles.feature_flag.exception.ResourceNotFoundException;
import org.aibles.feature_flag.metrics.FeatureFlagMetrics;
import org.aibles.feature_flag.repository.FeatureFlagRepository;
import org.aibles.feature_flag.repository.FlagEnvironmentStateRepository;
import org.aibles.feature_flag.service.EvaluationService;
import org.aibles.feature_flag.util.RolloutEvaluator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class EvaluationServiceImpl implements EvaluationService {

    private final FlagEnvironmentStateRepository flagStateRepository;
    private final FeatureFlagRepository featureFlagRepository;
    private final FeatureFlagMetrics metrics;

    @Override
    @Transactional(readOnly = true)
    public List<FlagEvaluationResponse> getAllFlags(Environment environment, String identifier) {
        return metrics.recordEvaluation(environment.getId().toString(), () ->
                flagStateRepository.findAllActiveByEnvironmentId(environment.getId())
                        .stream()
                        .map(state -> toResponse(state, identifier))
                        .toList());
    }

    @Override
    @Transactional(readOnly = true)
    public FlagEvaluationResponse getFlag(Environment environment, String flagKey, String identifier) {
        return metrics.recordEvaluation(environment.getId().toString(), () -> {
            FeatureFlag flag = featureFlagRepository
                    .findByProjectIdAndKey(environment.getProject().getId(), flagKey)
                    .orElseThrow(() -> new ResourceNotFoundException("Flag not found with key: " + flagKey));

            if (flag.isArchived()) {
                throw new ResourceNotFoundException("Flag not found with key: " + flagKey);
            }

            FlagEnvironmentState state = flagStateRepository
                    .findByFeatureFlagIdAndEnvironmentId(flag.getId(), environment.getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Flag state not found"));

            return toResponse(state, identifier);
        });
    }

    private FlagEvaluationResponse toResponse(FlagEnvironmentState state, String identifier) {
        boolean effective = RolloutEvaluator.evaluate(
                identifier,
                state.getFeatureFlag().getKey(),
                state.getRolloutPercent(),
                state.isEnabled()
        );
        return FlagEvaluationResponse.builder()
                .flagKey(state.getFeatureFlag().getKey())
                .enabled(effective)
                .value(effective ? state.getValue() : null)
                .valueType(state.getFeatureFlag().getValueType())
                .rolloutPercent(state.getRolloutPercent())
                .build();
    }
}
