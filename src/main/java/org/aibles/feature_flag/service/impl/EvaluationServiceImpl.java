package org.aibles.feature_flag.service.impl;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.domain.entity.FeatureFlag;
import org.aibles.feature_flag.domain.entity.FlagEnvironmentState;
import org.aibles.feature_flag.dto.response.FlagEvaluationResponse;
import org.aibles.feature_flag.exception.ResourceNotFoundException;
import org.aibles.feature_flag.hygiene.FlagEvaluationTracker;
import org.aibles.feature_flag.metrics.FeatureFlagMetrics;
import org.aibles.feature_flag.repository.FeatureFlagRepository;
import org.aibles.feature_flag.repository.FlagEnvironmentStateRepository;
import org.aibles.feature_flag.service.EvaluationService;
import org.aibles.feature_flag.util.RolloutEvaluator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EvaluationServiceImpl implements EvaluationService {

  private final FlagEnvironmentStateRepository flagStateRepository;
  private final FeatureFlagRepository featureFlagRepository;
  private final FeatureFlagMetrics metrics;
  private final FlagEvaluationTracker evaluationTracker;

  @Override
  @Transactional(readOnly = true)
  public List<FlagEvaluationResponse> getAllFlags(Environment environment, String identifier) {
    return metrics.recordEvaluation(
        environment.getId().toString(),
        () -> {
          List<FlagEvaluationResponse> responses =
              flagStateRepository.findAllActiveByEnvironmentId(environment.getId()).stream()
                  .map(state -> toResponse(state, identifier))
                  .toList();
          // Issue #37: throttled usage tracking. Called here — on the evaluation path itself,
          // outside any cache-load function — so that a cache hit (issue #30) still records the
          // evaluation. Tracking only on cache misses would report hot flags as stale.
          evaluationTracker.recordEnvironmentEvaluation(environment.getId());
          return responses;
        });
  }

  @Override
  @Transactional(readOnly = true)
  public FlagEvaluationResponse getFlag(
      Environment environment, String flagKey, String identifier) {
    return metrics.recordEvaluation(
        environment.getId().toString(),
        () -> {
          FeatureFlag flag =
              featureFlagRepository
                  .findByProjectIdAndKey(environment.getProject().getId(), flagKey)
                  .orElseThrow(
                      () -> new ResourceNotFoundException("Flag not found with key: " + flagKey));

          if (flag.isArchived()) {
            throw new ResourceNotFoundException("Flag not found with key: " + flagKey);
          }

          FlagEnvironmentState state =
              flagStateRepository
                  .findByFeatureFlagIdAndEnvironmentId(flag.getId(), environment.getId())
                  .orElseThrow(() -> new ResourceNotFoundException("Flag state not found"));

          FlagEvaluationResponse response = toResponse(state, identifier);
          evaluationTracker.recordFlagEvaluation(flag.getId(), environment.getId());
          return response;
        });
  }

  private FlagEvaluationResponse toResponse(FlagEnvironmentState state, String identifier) {
    boolean effective =
        RolloutEvaluator.evaluate(
            identifier,
            state.getFeatureFlag().getKey(),
            state.getRolloutPercent(),
            state.isEnabled());
    return FlagEvaluationResponse.builder()
        .flagKey(state.getFeatureFlag().getKey())
        .enabled(effective)
        .value(effective ? state.getValue() : null)
        .valueType(state.getFeatureFlag().getValueType())
        .rolloutPercent(state.getRolloutPercent())
        .build();
  }
}
