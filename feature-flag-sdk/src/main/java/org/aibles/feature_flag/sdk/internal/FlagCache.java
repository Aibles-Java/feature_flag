package org.aibles.feature_flag.sdk.internal;

import java.util.List;
import java.util.Optional;

/**
 * Cache contract for flag evaluation results.
 *
 * <p>{@link #get} returns a non-expired entry only; {@link #getStale} is the serve-stale fallback.
 */
public interface FlagCache extends AutoCloseable {

  /**
   * Returns the cached entry for {@code flagKey} only if it is fresh (not expired). Returns {@link
   * Optional#empty()} on a cache miss or if the entry has expired.
   */
  Optional<CacheEntry> get(String flagKey);

  /**
   * Returns the cached entry for {@code flagKey} regardless of expiry (stale-serve fallback).
   * Returns {@link Optional#empty()} if the key has never been cached.
   */
  Optional<CacheEntry> getStale(String flagKey);

  /** Stores an entry for the given key, replacing any existing entry. */
  void put(String flagKey, CacheEntry entry);

  /**
   * Stores all entries from the list, keyed by {@link CacheEntry#getFlagKey()}. Intended for bulk
   * pre-warm from {@code fetchAll}.
   */
  void putAll(List<CacheEntry> entries);

  /**
   * Shuts down the background eviction scheduler and releases resources. Idempotent — safe to call
   * multiple times.
   */
  @Override
  void close();
}
