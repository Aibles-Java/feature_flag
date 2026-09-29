package org.aibles.feature_flag.sdk.internal;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TTL-based in-memory flag cache backed by a {@link ConcurrentHashMap}.
 *
 * <p>ADR-SDK-002 limits:
 *
 * <ul>
 *   <li>{@code maxEntries} — once the map reaches this limit, new entries are silently dropped (old
 *       entries are not evicted to make room). Default 10 000.
 *   <li>{@code maxStaleSeconds} — the maximum age of a stale entry that may be served by {@link
 *       #getStale}. 0 means serve indefinitely. Default 0.
 * </ul>
 *
 * <p>A background {@link ScheduledExecutorService} runs expired-entry sweeps on a configurable
 * interval.
 */
public final class TtlFlagCache implements FlagCache {

  /** Default maximum number of cached entries (ADR-SDK-002). */
  private static final int DEFAULT_MAX_ENTRIES = 10_000;

  /** Eviction sweep period in seconds. */
  private static final long EVICTION_PERIOD_SECONDS = 30;

  private final long ttlNanos;
  private final long maxStaleNanos; // 0 means unlimited
  private final int maxEntries;
  private final ConcurrentHashMap<String, CacheEntry> store;
  private final ScheduledExecutorService scheduler;
  private final AtomicBoolean closed = new AtomicBoolean(false);

  /**
   * @param ttlSeconds fresh TTL in seconds; entries older than this are not returned by {@link
   *     #get}
   * @param maxStaleSeconds maximum age (seconds) at which a stale entry may be served via {@link
   *     #getStale}; 0 = unlimited
   */
  public TtlFlagCache(int ttlSeconds, int maxStaleSeconds) {
    this(ttlSeconds, maxStaleSeconds, DEFAULT_MAX_ENTRIES);
  }

  /**
   * @param ttlSeconds fresh TTL in seconds
   * @param maxStaleSeconds maximum stale age in seconds (0 = unlimited)
   * @param maxEntries hard cap on the number of cached entries
   */
  public TtlFlagCache(int ttlSeconds, int maxStaleSeconds, int maxEntries) {
    this.ttlNanos = TimeUnit.SECONDS.toNanos(ttlSeconds);
    this.maxStaleNanos = maxStaleSeconds == 0 ? 0L : TimeUnit.SECONDS.toNanos(maxStaleSeconds);
    this.maxEntries = maxEntries;
    this.store = new ConcurrentHashMap<>();

    // Daemon thread so it does not prevent JVM shutdown if close() is not called.
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "flag-cache-evict");
              t.setDaemon(true);
              return t;
            });
    scheduler.scheduleAtFixedRate(
        this::evictExpired, EVICTION_PERIOD_SECONDS, EVICTION_PERIOD_SECONDS, TimeUnit.SECONDS);
  }

  @Override
  public Optional<CacheEntry> get(String flagKey) {
    CacheEntry entry = store.get(flagKey);
    if (entry == null) {
      return Optional.empty();
    }
    long now = System.nanoTime();
    if (entry.isExpired(ttlNanos, now)) {
      return Optional.empty();
    }
    return Optional.of(entry);
  }

  @Override
  public Optional<CacheEntry> getStale(String flagKey) {
    CacheEntry entry = store.get(flagKey);
    if (entry == null) {
      return Optional.empty();
    }
    if (maxStaleNanos > 0) {
      long now = System.nanoTime();
      long ageNanos = now - entry.getCachedAtNanos();
      if (ageNanos > maxStaleNanos) {
        return Optional.empty();
      }
    }
    return Optional.of(entry);
  }

  @Override
  public void put(String flagKey, CacheEntry entry) {
    if (store.size() >= maxEntries && !store.containsKey(flagKey)) {
      // Cap reached — drop the new entry rather than evicting existing ones (ADR-SDK-002).
      return;
    }
    store.put(flagKey, entry);
  }

  @Override
  public void putAll(List<CacheEntry> entries) {
    for (CacheEntry entry : entries) {
      put(entry.getFlagKey(), entry);
    }
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      scheduler.shutdownNow();
    }
  }

  /**
   * Removes entries that should no longer be kept. An entry is removed only when it has exceeded
   * its TTL AND is also outside the serve-stale window (SF-1 fix):
   *
   * <ul>
   *   <li>If {@code maxStaleNanos > 0}: remove when age &gt; maxStaleNanos (outside stale window).
   *   <li>If {@code maxStaleNanos == 0} (unlimited): never evict expired entries — they may be
   *       served stale indefinitely per ADR-SDK-002.
   * </ul>
   *
   * <p>Called by the background scheduler. Also exposed as {@link #evictExpiredForTest()} for unit
   * tests.
   */
  private void evictExpired() {
    if (maxStaleNanos == 0) {
      // Unlimited stale: never evict — entries may be served stale forever (ADR-SDK-002).
      return;
    }
    long now = System.nanoTime();
    store
        .entrySet()
        .removeIf(
            e -> {
              CacheEntry entry = e.getValue();
              // Remove only when the entry is beyond both the TTL AND the stale window.
              long ageNanos = now - entry.getCachedAtNanos();
              return ageNanos > maxStaleNanos;
            });
  }

  /**
   * Test hook that triggers {@link #evictExpired()} synchronously. Exists solely to allow unit
   * tests (in a sibling package) to verify eviction behaviour without waiting for the scheduler.
   * Public visibility is required because the tests live in a different package; it performs no
   * security-sensitive action (it only runs the same sweep the scheduler runs).
   */
  public void evictExpiredForTest() {
    evictExpired();
  }

  /** Returns the current number of entries in the cache. Visible for testing. */
  public int size() {
    return store.size();
  }
}
