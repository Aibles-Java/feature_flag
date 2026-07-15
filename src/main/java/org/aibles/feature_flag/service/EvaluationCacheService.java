package org.aibles.feature_flag.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

public interface EvaluationCacheService {
  Optional<List<FlagStateSnapshot>> get(UUID environmentId);

  void put(UUID environmentId, List<FlagStateSnapshot> snapshots);

  void evict(UUID environmentId);

  /**
   * Schedules eviction to run after the current transaction commits. If no transaction is active,
   * evicts immediately. Prevents a concurrent SDK read in the [evict, commit] window from
   * re-populating the cache with the pre-change row under READ COMMITTED isolation.
   */
  void evictAfterCommit(UUID environmentId);

  /**
   * Atomically returns the cached snapshots for {@code environmentId}, invoking {@code loader} at
   * most once if absent. Concurrent callers for the same key block until the first load completes —
   * preventing thundering-herd DB hits on a cold cache.
   */
  List<FlagStateSnapshot> getOrLoad(
      UUID environmentId, Function<UUID, List<FlagStateSnapshot>> loader);
}
