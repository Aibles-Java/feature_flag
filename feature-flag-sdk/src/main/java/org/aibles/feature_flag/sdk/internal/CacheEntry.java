package org.aibles.feature_flag.sdk.internal;

import org.aibles.feature_flag.sdk.FlagValueType;

/**
 * Immutable snapshot of a single flag evaluation result, held in {@link TtlFlagCache}.
 *
 * <p>Time is expressed in nanoseconds from {@link System#nanoTime()} for monotonic comparison.
 */
public final class CacheEntry {

  private final String flagKey;
  private final boolean enabled;
  private final String value;
  private final FlagValueType valueType;
  private final int rolloutPercent;
  private final long cachedAtNanos;

  public CacheEntry(
      String flagKey,
      boolean enabled,
      String value,
      FlagValueType valueType,
      int rolloutPercent,
      long cachedAtNanos) {
    this.flagKey = flagKey;
    this.enabled = enabled;
    this.value = value;
    this.valueType = valueType;
    this.rolloutPercent = rolloutPercent;
    this.cachedAtNanos = cachedAtNanos;
  }

  public String getFlagKey() {
    return flagKey;
  }

  public boolean isEnabled() {
    return enabled;
  }

  /** The raw string value from the server. May be null when {@code enabled == false}. */
  public String getValue() {
    return value;
  }

  public FlagValueType getValueType() {
    return valueType;
  }

  public int getRolloutPercent() {
    return rolloutPercent;
  }

  public long getCachedAtNanos() {
    return cachedAtNanos;
  }

  /**
   * Returns true when this entry is older than {@code ttlNanos} relative to {@code nowNanos}.
   *
   * @param ttlNanos TTL in nanoseconds
   * @param nowNanos current monotonic clock value from {@link System#nanoTime()}
   */
  public boolean isExpired(long ttlNanos, long nowNanos) {
    return (nowNanos - cachedAtNanos) >= ttlNanos;
  }

  /**
   * Returns true when this flag is on a partial rollout (i.e. rolloutPercent is strictly between 0
   * and 100 exclusive). Rollout flags bypass the cache per ADR-SDK-004.
   */
  public boolean isRollout() {
    return rolloutPercent > 0 && rolloutPercent < 100;
  }
}
