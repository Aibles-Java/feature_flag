package org.aibles.feature_flag.service.impl;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.domain.entity.FlagEnvironmentState;
import org.aibles.feature_flag.dto.response.FlagEvaluationResponse;
import org.aibles.feature_flag.exception.ResourceNotFoundException;
import org.aibles.feature_flag.hygiene.FlagEvaluationTracker;
import org.aibles.feature_flag.metrics.FeatureFlagMetrics;
import org.aibles.feature_flag.repository.FlagEnvironmentStateRepository;
import org.aibles.feature_flag.service.EvaluationCacheService;
import org.aibles.feature_flag.service.EvaluationService;
import org.aibles.feature_flag.service.FlagStateSnapshot;
import org.aibles.feature_flag.util.RolloutEvaluator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Note: @Transactional(readOnly=true) is declared at each public method so a DB connection is
// acquired lazily - cache hits never open a connection. The cache.get(key, loader) inside
// getOrLoad() is called while the read transaction is still active; this is safe because
// PostgreSQL READ COMMITTED read-only transactions never roll back on a clean read, so there
// is no risk of caching data from an aborted transaction.

@Service
@RequiredArgsConstructor
public class EvaluationServiceImpl implements EvaluationService {

  private final FlagEnvironmentStateRepository flagStateRepository;
  private final EvaluationCacheService evaluationCacheService;
  private final FeatureFlagMetrics metrics;
  private final FlagEvaluationTracker evaluationTracker;

  @Override
  @Transactional(readOnly = true)
  public List<FlagEvaluationResponse> getAllFlags(Environment environment, String identifier) {
    return metrics.recordEvaluation(
        environment.getId().toString(),
        () -> {
          List<FlagStateSnapshot> snapshots = getOrLoadSnapshots(environment);
          List<FlagEvaluationResponse> responses =
              snapshots.stream().map(s -> toResponse(s, identifier)).toList();
          // Issue #37: throttled usage tracking. Deliberately out here rather than inside the
          // cache loader - a hit would skip it and the busiest environments would report as
          // stale, which is precisely backwards.
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
          List<FlagStateSnapshot> snapshots = getOrLoadSnapshots(environment);
          FlagStateSnapshot snapshot =
              snapshots.stream()
                  .filter(s -> flagKey.equals(s.flagKey()))
                  .findFirst()
                  .orElseThrow(
                      () -> new ResourceNotFoundException("Flag not found with key: " + flagKey));
          FlagEvaluationResponse response = toResponse(snapshot, identifier);
          // Same reasoning, and the reason the snapshot carries flagId: looking the id up here
          // would put back the query the cache exists to remove.
          evaluationTracker.recordFlagEvaluation(snapshot.flagId(), environment.getId());
          return response;
        });
  }

  /**
   * The archived filter lives in the query, so archived flags never enter the cache and {@code
   * getFlag} reports them as not found without a separate check.
   */
  private List<FlagStateSnapshot> getOrLoadSnapshots(Environment environment) {
    return evaluationCacheService.getOrLoad(
        environment.getId(),
        id ->
            flagStateRepository.findAllActiveByEnvironmentId(id).stream()
                .map(this::toSnapshot)
                .toList());
  }

  private FlagStateSnapshot toSnapshot(FlagEnvironmentState state) {
    return new FlagStateSnapshot(
        state.getFeatureFlag().getId(),
        state.getFeatureFlag().getKey(),
        state.isEnabled(),
        state.getValue(),
        state.getFeatureFlag().getValueType(),
        state.getRolloutPercent());
  }

  private FlagEvaluationResponse toResponse(FlagStateSnapshot snapshot, String identifier) {
    boolean effective =
        RolloutEvaluator.evaluate(
            identifier, snapshot.flagKey(), snapshot.rolloutPercent(), snapshot.enabled());
    return FlagEvaluationResponse.builder()
        .flagKey(snapshot.flagKey())
        .enabled(effective)
        .value(effective ? snapshot.value() : null)
        .valueType(snapshot.valueType())
        .rolloutPercent(snapshot.rolloutPercent())
        .build();
  }
}
