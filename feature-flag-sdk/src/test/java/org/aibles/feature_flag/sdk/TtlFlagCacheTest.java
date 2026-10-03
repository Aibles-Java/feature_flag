package org.aibles.feature_flag.sdk;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import org.aibles.feature_flag.sdk.internal.CacheEntry;
import org.aibles.feature_flag.sdk.internal.TtlFlagCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link TtlFlagCache}. */
class TtlFlagCacheTest {

  private TtlFlagCache cache;

  @BeforeEach
  void setUp() {
    // 60s TTL, no stale limit, default max entries
    cache = new TtlFlagCache(60, 0);
  }

  @AfterEach
  void tearDown() {
    cache.close();
  }

  // ---------------------------------------------------------------------------
  // Happy-path hit
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("get returns fresh entry")
  void getFreshEntry() {
    CacheEntry entry = entry("flag-a", true, "true", FlagValueType.BOOLEAN, 0);
    cache.put("flag-a", entry);
    Optional<CacheEntry> result = cache.get("flag-a");
    assertTrue(result.isPresent());
    assertEquals("flag-a", result.get().getFlagKey());
  }

  // ---------------------------------------------------------------------------
  // Cache miss
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("get returns empty for unknown key")
  void getMissForUnknownKey() {
    Optional<CacheEntry> result = cache.get("no-such-flag");
    assertTrue(result.isEmpty());
  }

  // ---------------------------------------------------------------------------
  // Expiry
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("get returns empty for expired entry (TTL=1s, cached 2s ago)")
  void getEmptyForExpiredEntry() {
    long twoSecondsAgoNanos = System.nanoTime() - 2_000_000_000L;
    CacheEntry stale =
        new CacheEntry("flag-b", false, null, FlagValueType.BOOLEAN, 0, twoSecondsAgoNanos);
    // Use a 1-second TTL cache for this test
    try (TtlFlagCache shortCache = new TtlFlagCache(1, 0)) {
      shortCache.put("flag-b", stale);
      assertTrue(shortCache.get("flag-b").isEmpty(), "Expired entry must not be returned by get");
    }
  }

  // ---------------------------------------------------------------------------
  // Serve-stale
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("getStale returns expired entry when maxStaleSeconds=0 (unlimited)")
  void getStaleReturnsExpiredEntryUnlimitedStale() {
    long twoSecondsAgoNanos = System.nanoTime() - 2_000_000_000L;
    CacheEntry stale =
        new CacheEntry("flag-c", true, "stale-val", FlagValueType.STRING, 0, twoSecondsAgoNanos);
    try (TtlFlagCache shortCache = new TtlFlagCache(1, 0)) {
      shortCache.put("flag-c", stale);
      Optional<CacheEntry> result = shortCache.getStale("flag-c");
      assertTrue(result.isPresent(), "getStale must return expired entry when maxStaleSeconds=0");
      assertEquals("stale-val", result.get().getValue());
    }
  }

  @Test
  @DisplayName("getStale returns empty when entry exceeds maxStaleSeconds bound")
  void getStaleEmptyWhenExceedsMaxStale() {
    long tenSecondsAgoNanos = System.nanoTime() - 10_000_000_000L;
    CacheEntry old =
        new CacheEntry("flag-d", true, "old-val", FlagValueType.STRING, 0, tenSecondsAgoNanos);
    // maxStaleSeconds=5 — a 10s-old entry exceeds the bound
    try (TtlFlagCache boundedCache = new TtlFlagCache(1, 5)) {
      boundedCache.put("flag-d", old);
      Optional<CacheEntry> result = boundedCache.getStale("flag-d");
      assertTrue(result.isEmpty(), "getStale must return empty when entry exceeds maxStaleSeconds");
    }
  }

  // ---------------------------------------------------------------------------
  // putAll
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("putAll stores all entries and they are retrievable")
  void putAllStoresAllEntries() {
    List<CacheEntry> entries =
        List.of(
            entry("alpha", true, "1", FlagValueType.INTEGER, 0),
            entry("beta", false, null, FlagValueType.BOOLEAN, 0),
            entry("gamma", true, "hello", FlagValueType.STRING, 0));
    cache.putAll(entries);
    assertTrue(cache.get("alpha").isPresent());
    assertTrue(cache.get("beta").isPresent());
    assertTrue(cache.get("gamma").isPresent());
  }

  // ---------------------------------------------------------------------------
  // maxEntries cap (ADR-SDK-002)
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("maxEntries cap: entries beyond cap are silently dropped")
  void maxEntriesCapDropsNewEntries() {
    try (TtlFlagCache tiny = new TtlFlagCache(60, 0, 2)) {
      tiny.put("k1", entry("k1", true, "v1", FlagValueType.STRING, 0));
      tiny.put("k2", entry("k2", true, "v2", FlagValueType.STRING, 0));
      tiny.put("k3", entry("k3", true, "v3", FlagValueType.STRING, 0)); // over cap → dropped
      assertEquals(2, tiny.size(), "Cache must not exceed maxEntries");
      assertTrue(tiny.get("k1").isPresent());
      assertTrue(tiny.get("k2").isPresent());
      assertTrue(tiny.get("k3").isEmpty(), "Third entry must be silently dropped");
    }
  }

  // ---------------------------------------------------------------------------
  // close idempotency
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("close() is idempotent")
  void closeIsIdempotent() {
    TtlFlagCache c = new TtlFlagCache(60, 0);
    assertDoesNotThrow(c::close);
    assertDoesNotThrow(c::close);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static CacheEntry entry(
      String key, boolean enabled, String value, FlagValueType type, int rolloutPercent) {
    return new CacheEntry(key, enabled, value, type, rolloutPercent, System.nanoTime());
  }
}
