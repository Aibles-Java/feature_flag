package org.aibles.feature_flag.sdk;

import static org.junit.jupiter.api.Assertions.*;

import org.aibles.feature_flag.sdk.internal.RetryPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RetryPolicy}. No real sleep — asserts nextDelayMs math and shouldRetry
 * logic only.
 */
class RetryPolicyTest {

  // ---------------------------------------------------------------------------
  // shouldRetry — positive cases
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("shouldRetry true for 5xx status within maxRetries")
  void shouldRetryTrueFor5xx() {
    RetryPolicy policy = new RetryPolicy(100, 2.0, 3, 0.0);
    assertTrue(policy.shouldRetry(500, 0));
    assertTrue(policy.shouldRetry(503, 1));
    assertTrue(policy.shouldRetry(500, 2));
  }

  @Test
  @DisplayName("shouldRetry true for network error (status=-1) within maxRetries")
  void shouldRetryTrueForNetworkError() {
    RetryPolicy policy = new RetryPolicy(100, 2.0, 3, 0.0);
    assertTrue(policy.shouldRetry(-1, 0));
  }

  @Test
  @DisplayName("shouldRetry true for 408 (request timeout) within maxRetries")
  void shouldRetryTrueFor408() {
    RetryPolicy policy = new RetryPolicy(100, 2.0, 3, 0.0);
    assertTrue(policy.shouldRetry(408, 0));
  }

  @Test
  @DisplayName("shouldRetry true for 429 (rate limit) within maxRetries")
  void shouldRetryTrueFor429() {
    RetryPolicy policy = new RetryPolicy(100, 2.0, 3, 0.0);
    assertTrue(policy.shouldRetry(429, 0));
  }

  // ---------------------------------------------------------------------------
  // shouldRetry — no-retry status codes
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("shouldRetry false for 401 (no-retry set)")
  void shouldRetryFalseFor401() {
    RetryPolicy policy = new RetryPolicy(100, 2.0, 3, 0.0);
    assertFalse(policy.shouldRetry(401, 0));
  }

  @Test
  @DisplayName("shouldRetry false for 403 (no-retry set)")
  void shouldRetryFalseFor403() {
    RetryPolicy policy = new RetryPolicy(100, 2.0, 3, 0.0);
    assertFalse(policy.shouldRetry(403, 0));
  }

  @Test
  @DisplayName("shouldRetry false for 404 (no-retry set)")
  void shouldRetryFalseFor404() {
    RetryPolicy policy = new RetryPolicy(100, 2.0, 3, 0.0);
    assertFalse(policy.shouldRetry(404, 0));
  }

  // ---------------------------------------------------------------------------
  // shouldRetry — attempt exhaustion
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("shouldRetry false when attempt equals maxRetries")
  void shouldRetryFalseWhenAttemptsExhausted() {
    RetryPolicy policy = new RetryPolicy(100, 2.0, 3, 0.0);
    // attempt=3 == maxRetries=3
    assertFalse(policy.shouldRetry(500, 3));
  }

  @Test
  @DisplayName("shouldRetry false when attempt exceeds maxRetries")
  void shouldRetryFalseWhenAttemptsExceeded() {
    RetryPolicy policy = new RetryPolicy(100, 2.0, 3, 0.0);
    assertFalse(policy.shouldRetry(500, 10));
  }

  // ---------------------------------------------------------------------------
  // nextDelayMs — backoff math (no real sleep)
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("nextDelayMs without jitter: attempt 0 = initialDelay")
  void nextDelayMsAttempt0NoJitter() {
    RetryPolicy policy = new RetryPolicy(100, 2.0, 3, 0.0);
    long delay = policy.nextDelayMs(0);
    assertEquals(100L, delay);
  }

  @Test
  @DisplayName("nextDelayMs without jitter: attempt 1 = initialDelay * multiplier")
  void nextDelayMsAttempt1NoJitter() {
    RetryPolicy policy = new RetryPolicy(100, 2.0, 3, 0.0);
    long delay = policy.nextDelayMs(1);
    assertEquals(200L, delay);
  }

  @Test
  @DisplayName("nextDelayMs without jitter: attempt 2 = initialDelay * multiplier^2")
  void nextDelayMsAttempt2NoJitter() {
    RetryPolicy policy = new RetryPolicy(100, 2.0, 3, 0.0);
    long delay = policy.nextDelayMs(2);
    assertEquals(400L, delay);
  }

  @Test
  @DisplayName("nextDelayMs with jitter is within [base, base*(1+jitter)]")
  void nextDelayMsWithJitterInBounds() {
    RetryPolicy policy = new RetryPolicy(100, 2.0, 3, 0.20);
    long base = 100L;
    long upper = Math.round(base * 1.20);
    for (int i = 0; i < 20; i++) {
      long delay = policy.nextDelayMs(0);
      assertTrue(
          delay >= base && delay <= upper,
          "Delay " + delay + " must be in [" + base + ", " + upper + "]");
    }
  }

  // ---------------------------------------------------------------------------
  // DEFAULT policy smoke-test
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("DEFAULT policy: 100ms, x2, 3 retries, 20% jitter")
  void defaultPolicyValues() {
    RetryPolicy p = RetryPolicy.DEFAULT;
    assertEquals(100L, p.getInitialDelayMs());
    assertEquals(2.0, p.getMultiplier());
    assertEquals(3, p.getMaxRetries());
    assertEquals(0.20, p.getJitterFraction());
  }

  // ---------------------------------------------------------------------------
  // Constructor validation
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("constructor rejects negative initialDelayMs")
  void constructorRejectsNegativeInitialDelay() {
    assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(-1, 2.0, 3, 0.0));
  }

  @Test
  @DisplayName("constructor rejects multiplier < 1.0")
  void constructorRejectsMultiplierBelowOne() {
    assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(100, 0.5, 3, 0.0));
  }

  @Test
  @DisplayName("constructor rejects jitterFraction > 1.0")
  void constructorRejectsJitterAboveOne() {
    assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(100, 2.0, 3, 1.5));
  }
}
