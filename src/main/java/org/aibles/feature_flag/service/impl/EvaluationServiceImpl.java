package org.aibles.feature_flag.service.impl;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.domain.entity.FlagEnvironmentState;
import org.aibles.feature_flag.dto.response.FlagEvaluationResponse;
import org.aibles.feature_flag.exception.ResourceNotFoundException;
import org.aibles.feature_flag.repository.FlagEnvironmentStateRepository;
import org.aibles.feature_flag.service.EvaluationCacheService;
import org.aibles.feature_flag.service.EvaluationService;
import org.aibles.feature_flag.service.FlagStateSnapshot;
import org.aibles.feature_flag.util.RolloutEvaluator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EvaluationServiceImpl implements EvaluationService {

  private final FlagEnvironmentStateRepository flagStateRepository;
  private final EvaluationCacheService evaluationCacheService;

  @Override
  @Transactional(readOnly = true)
  public List<FlagEvaluationResponse> getAllFlags(Environment environment, String identifier) {
    List<FlagStateSnapshot> snapshots = getOrLoadSnapshots(environment);
    return snapshots.stream().map(s -> toResponse(s, identifier)).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public FlagEvaluationResponse getFlag(
      Environment environment, String flagKey, String identifier) {
    List<FlagStateSnapshot> snapshots = getOrLoadSnapshots(environment);
    FlagStateSnapshot snapshot =
        snapshots.stream()
            .filter(s -> flagKey.equals(s.flagKey()))
            .findFirst()
            .orElseThrow(
                () -> new ResourceNotFoundException("Flag not found with key: " + flagKey));
    return toResponse(snapshot, identifier);
  }

  private List<FlagStateSnapshot> getOrLoadSnapshots(Environment environment) {
    return evaluationCacheService
        .get(environment.getId())
        .orElseGet(
            () -> {
              List<FlagStateSnapshot> fresh =
                  flagStateRepository.findAllActiveByEnvironmentId(environment.getId()).stream()
                      .map(this::toSnapshot)
                      .toList();
              evaluationCacheService.put(environment.getId(), fresh);
              return fresh;
            });
  }

  private FlagStateSnapshot toSnapshot(FlagEnvironmentState state) {
    return new FlagStateSnapshot(
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
