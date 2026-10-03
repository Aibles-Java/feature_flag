package org.aibles.feature_flag.sdk.internal;

import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential-backoff retry policy with jitter.
 *
 * <p>Default: 100 ms initial delay, x2 multiplier, 3 max retries, 20% jitter fraction.
 *
 * <p>HTTP status codes 401, 403, and 404 are never retried (client-error no-retry set).
 */
public final class RetryPolicy {

  /** Status codes that must not be retried. */
  private static final Set<Integer> NO_RETRY_STATUS_CODES = Set.of(401, 403, 404);

  /** Canonical default policy. */
  public static final RetryPolicy DEFAULT = new RetryPolicy(100, 2.0, 3, 0.20);

  private final long initialDelayMs;
  private final double multiplier;
  private final int maxRetries;
  private final double jitterFraction;

  /**
   * @param initialDelayMs base delay before the first retry (milliseconds)
   * @param multiplier exponential growth factor applied on each successive attempt
   * @param maxRetries maximum number of retry attempts (0 = no retries)
   * @param jitterFraction fraction of the computed delay to add as random jitter (0.0 = no jitter)
   */
  public RetryPolicy(
      long initialDelayMs, double multiplier, int maxRetries, double jitterFraction) {
    if (initialDelayMs < 0) throw new IllegalArgumentException("initialDelayMs must be >= 0");
    if (multiplier < 1.0) throw new IllegalArgumentException("multiplier must be >= 1.0");
    if (maxRetries < 0) throw new IllegalArgumentException("maxRetries must be >= 0");
    if (jitterFraction < 0.0 || jitterFraction > 1.0) {
      throw new IllegalArgumentException("jitterFraction must be in [0.0, 1.0]");
    }
    this.initialDelayMs = initialDelayMs;
    this.multiplier = multiplier;
    this.maxRetries = maxRetries;
    this.jitterFraction = jitterFraction;
  }

  /**
   * Returns true when another attempt should be made.
   *
   * @param statusCode the HTTP status code received, or -1 for a network/IO failure (always retried
   *     up to {@code maxRetries})
   * @param attempt the 0-based attempt index just completed (attempt=0 → first attempt failed)
   */
  public boolean shouldRetry(int statusCode, int attempt) {
    if (attempt >= maxRetries) {
      return false;
    }
    if (statusCode != -1 && NO_RETRY_STATUS_CODES.contains(statusCode)) {
      return false;
    }
    return true;
  }

  /**
   * Computes the next delay in milliseconds for the given attempt.
   *
   * @param attempt 0-based index (0 → delay before the first retry)
   */
  public long nextDelayMs(int attempt) {
    double base = initialDelayMs * Math.pow(multiplier, attempt);
    double jitter = base * jitterFraction * ThreadLocalRandom.current().nextDouble();
    return Math.round(base + jitter);
  }

  public long getInitialDelayMs() {
    return initialDelayMs;
  }

  public double getMultiplier() {
    return multiplier;
  }

  public int getMaxRetries() {
    return maxRetries;
  }

  public double getJitterFraction() {
    return jitterFraction;
  }

  public Set<Integer> getNoRetryStatusCodes() {
    return NO_RETRY_STATUS_CODES;
  }
}
