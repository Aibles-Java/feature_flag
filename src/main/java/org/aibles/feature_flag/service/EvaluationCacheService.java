package org.aibles.feature_flag.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EvaluationCacheService {
  Optional<List<FlagStateSnapshot>> get(UUID environmentId);

  void put(UUID environmentId, List<FlagStateSnapshot> snapshots);

  void evict(UUID environmentId);
}
