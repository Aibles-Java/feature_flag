package org.aibles.feature_flag.sdk;

import static org.junit.jupiter.api.Assertions.*;

import org.aibles.feature_flag.sdk.internal.CacheEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link CacheEntry}. */
class CacheEntryTest {

  private static final long SEC_NS = 1_000_000_000L;

  @Test
  @DisplayName("isExpired returns false when entry is within TTL")
  void isExpiredFalseWhenFresh() {
    long now = System.nanoTime();
    CacheEntry entry = new CacheEntry("flag-a", true, "true", FlagValueType.BOOLEAN, 0, now);
    // ttlNanos = 60s, nowNanos = cached + 30s → not expired
    assertFalse(entry.isExpired(60 * SEC_NS, now + 30 * SEC_NS));
  }

  @Test
  @DisplayName("isExpired returns true when entry is past TTL")
  void isExpiredTrueWhenStale() {
    long now = System.nanoTime();
    CacheEntry entry = new CacheEntry("flag-b", false, null, FlagValueType.BOOLEAN, 0, now);
    // ttlNanos = 60s, nowNanos = cached + 61s → expired
    assertTrue(entry.isExpired(60 * SEC_NS, now + 61 * SEC_NS));
  }

  @Test
  @DisplayName("isExpired returns true at exact TTL boundary (>= semantics)")
  void isExpiredAtExactBoundary() {
    long cachedAt = 1_000_000_000L;
    long ttl = 60 * SEC_NS;
    CacheEntry entry = new CacheEntry("flag-c", true, "hello", FlagValueType.STRING, 0, cachedAt);
    // exactly at boundary → expired
    assertTrue(entry.isExpired(ttl, cachedAt + ttl));
  }

  @Test
  @DisplayName("isRollout returns true for rolloutPercent in (0, 100)")
  void isRolloutTrueForPartialRollout() {
    CacheEntry entry = new CacheEntry("flag-d", true, null, FlagValueType.BOOLEAN, 50, 0L);
    assertTrue(entry.isRollout());
  }

  @Test
  @DisplayName("isRollout returns false for rolloutPercent=0 (fully off)")
  void isRolloutFalseForZeroPercent() {
    CacheEntry entry = new CacheEntry("flag-e", false, null, FlagValueType.BOOLEAN, 0, 0L);
    assertFalse(entry.isRollout());
  }

  @Test
  @DisplayName("isRollout returns false for rolloutPercent=100 (fully on)")
  void isRolloutFalseForFullPercent() {
    CacheEntry entry = new CacheEntry("flag-f", true, "true", FlagValueType.BOOLEAN, 100, 0L);
    assertFalse(entry.isRollout());
  }

  @Test
  @DisplayName("accessors return values from constructor")
  void accessorsReturnConstructorValues() {
    long ts = 42_000L;
    CacheEntry entry = new CacheEntry("my-flag", true, "hello", FlagValueType.STRING, 25, ts);
    assertEquals("my-flag", entry.getFlagKey());
    assertTrue(entry.isEnabled());
    assertEquals("hello", entry.getValue());
    assertEquals(FlagValueType.STRING, entry.getValueType());
    assertEquals(25, entry.getRolloutPercent());
    assertEquals(ts, entry.getCachedAtNanos());
  }
}
