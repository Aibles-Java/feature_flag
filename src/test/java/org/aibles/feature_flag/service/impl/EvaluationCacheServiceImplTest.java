package org.aibles.feature_flag.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.List;
import java.util.UUID;
import org.aibles.feature_flag.domain.enums.FlagValueType;
import org.aibles.feature_flag.service.FlagStateSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EvaluationCacheServiceImplTest {

  Cache<UUID, List<FlagStateSnapshot>> rawCache;
  EvaluationCacheServiceImpl cacheService;

  @BeforeEach
  void setUp() {
    rawCache = Caffeine.newBuilder().maximumSize(100).build();
    cacheService = new EvaluationCacheServiceImpl(rawCache);
  }

  @Test
  void get_returnsEmpty_whenCacheMiss() {
    assertThat(cacheService.get(UUID.randomUUID())).isEmpty();
  }

  @Test
  void putAndGet_returnsStoredSnapshots() {
    UUID envId = UUID.randomUUID();
    List<FlagStateSnapshot> snapshots =
        List.of(new FlagStateSnapshot("flag-a", true, "on", FlagValueType.BOOLEAN, 100));

    cacheService.put(envId, snapshots);

    assertThat(cacheService.get(envId)).isPresent().contains(snapshots);
  }

  @Test
  void evict_removesEntry() {
    UUID envId = UUID.randomUUID();
    cacheService.put(
        envId, List.of(new FlagStateSnapshot("flag-b", false, null, FlagValueType.BOOLEAN, 0)));

    cacheService.evict(envId);

    assertThat(cacheService.get(envId)).isEmpty();
  }

  @Test
  void evict_unknownKey_doesNotThrow() {
    cacheService.evict(UUID.randomUUID());
  }

  @Test
  void getOrLoad_returnsExistingEntry_withoutInvokingLoader() {
    UUID envId = UUID.randomUUID();
    List<FlagStateSnapshot> cached =
        List.of(new FlagStateSnapshot("flag-d", true, null, FlagValueType.BOOLEAN, 100));
    cacheService.put(envId, cached);

    List<FlagStateSnapshot> result =
        cacheService.getOrLoad(envId, id -> List.of()); // loader would return empty

    assertThat(result).isEqualTo(cached);
  }

  @Test
  void getOrLoad_invokesLoader_onCacheMiss() {
    UUID envId = UUID.randomUUID();
    List<FlagStateSnapshot> loaded =
        List.of(new FlagStateSnapshot("flag-e", false, null, FlagValueType.BOOLEAN, 0));

    List<FlagStateSnapshot> result = cacheService.getOrLoad(envId, id -> loaded);

    assertThat(result).isEqualTo(loaded);
    assertThat(cacheService.get(envId)).isPresent().contains(loaded);
  }

  @Test
  void put_overwritesPreviousValue() {
    UUID envId = UUID.randomUUID();
    List<FlagStateSnapshot> first =
        List.of(new FlagStateSnapshot("flag-c", true, null, FlagValueType.BOOLEAN, 100));
    List<FlagStateSnapshot> second =
        List.of(new FlagStateSnapshot("flag-c", false, null, FlagValueType.BOOLEAN, 0));

    cacheService.put(envId, first);
    cacheService.put(envId, second);

    assertThat(cacheService.get(envId)).isPresent().contains(second);
  }
}
