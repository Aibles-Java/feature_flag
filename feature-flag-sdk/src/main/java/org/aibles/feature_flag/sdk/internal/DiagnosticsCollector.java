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

  /** Returns an immutable snapshot of current counter values. */
  public DiagnosticsSnapshot snapshot() {
    return new DiagnosticsSnapshot(
        serverErrors.get(), cacheHits.get(), cacheMisses.get(), invalidKeyEvents.get());
  }

  /**
   * Immutable point-in-time snapshot of SDK diagnostics counters.
   *
   * @param serverErrors number of server-side errors (5xx / network failures)
   * @param cacheHits number of times a fresh cache entry was returned
   * @param cacheMisses number of times the cache was consulted and no fresh entry was found
   * @param invalidKeyEvents number of HTTP 401 responses received
   */
  public record DiagnosticsSnapshot(
      long serverErrors, long cacheHits, long cacheMisses, long invalidKeyEvents) {

    /**
     * Returns the cache hit ratio as a value in [0.0, 1.0], or {@code 0.0} when no lookups have
     * been made.
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
