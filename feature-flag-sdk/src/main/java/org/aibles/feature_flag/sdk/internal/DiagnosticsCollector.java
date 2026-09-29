package org.aibles.feature_flag.sdk.internal;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe diagnostics counters for the SDK.
 *
 * <p>All counters are monotonically increasing. Call {@link #snapshot()} to get an immutable point-
 * in-time copy.
 */
public final class DiagnosticsCollector {

  private final AtomicLong serverErrors = new AtomicLong();
  private final AtomicLong cacheHits = new AtomicLong();
  private final AtomicLong cacheMisses = new AtomicLong();
  private final AtomicLong invalidKeyEvents = new AtomicLong();

  public void recordServerError() {
    serverErrors.incrementAndGet();
  }

  public void recordCacheHit() {
    cacheHits.incrementAndGet();
  }

  public void recordCacheMiss() {
    cacheMisses.incrementAndGet();
  }

  public void recordInvalidKeyEvent() {
    invalidKeyEvents.incrementAndGet();
  }

  /**
   * Returns an immutable snapshot of current counter values with {@code cacheSize} set to 0. Prefer
   * {@link #snapshot(int)} from {@code FlagClient} which supplies the real cache size.
   */
  public DiagnosticsSnapshot snapshot() {
    return snapshot(0);
  }

  /**
   * Returns an immutable snapshot of current counter values.
   *
   * @param cacheSize current number of entries in the cache (D4 heap-monitoring mitigation / SF-2)
   */
  public DiagnosticsSnapshot snapshot(int cacheSize) {
    return new DiagnosticsSnapshot(
        serverErrors.get(), cacheHits.get(), cacheMisses.get(), invalidKeyEvents.get(), cacheSize);
  }

  /**
   * Immutable point-in-time snapshot of SDK diagnostics counters.
   *
   * @param serverErrors number of server-side errors (5xx / network failures)
   * @param cacheHits number of times a fresh cache entry was returned
   * @param cacheMisses number of times the cache was consulted and no fresh entry was found
   * @param invalidKeyEvents number of HTTP 401 responses received
   * @param cacheSize current number of entries in the cache at snapshot time (SF-2 / D4)
   */
  public record DiagnosticsSnapshot(
      long serverErrors, long cacheHits, long cacheMisses, long invalidKeyEvents, int cacheSize) {

    /**
     * Returns the cache hit ratio as a value in [0.0, 1.0], or {@code 0.0} when no lookups have
     * been made.
     *
     * <p>Note: rollout-flag evaluations that bypass the cache are NOT counted in {@code cacheHits}
     * or {@code cacheMisses} because they always go to {@code fetchOne} (ADR-SDK-004). The ratio
     * therefore reflects only non-rollout lookups.
     */
    public double hitRatio() {
      long total = cacheHits + cacheMisses;
      if (total == 0) {
        return 0.0;
      }
      return (double) cacheHits / total;
    }
  }
}
