package org.aibles.feature_flag.service.impl;

import com.github.benmanes.caffeine.cache.Cache;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.aibles.feature_flag.service.EvaluationCacheService;
import org.aibles.feature_flag.service.FlagStateSnapshot;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EvaluationCacheServiceImpl implements EvaluationCacheService {

  private final Cache<UUID, List<FlagStateSnapshot>> evaluationCache;

  @Override
  public Optional<List<FlagStateSnapshot>> get(UUID environmentId) {
    return Optional.ofNullable(evaluationCache.getIfPresent(environmentId));
  }

  @Override
  public void put(UUID environmentId, List<FlagStateSnapshot> snapshots) {
    evaluationCache.put(environmentId, snapshots);
  }

  @Override
  public void evict(UUID environmentId) {
    evaluationCache.invalidate(environmentId);
  }

  @Override
  public List<FlagStateSnapshot> getOrLoad(
      UUID environmentId, Function<UUID, List<FlagStateSnapshot>> loader) {
    return evaluationCache.get(environmentId, loader);
  }
}
