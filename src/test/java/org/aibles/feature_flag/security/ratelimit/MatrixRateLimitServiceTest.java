package org.aibles.feature_flag.security.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.aibles.feature_flag.testsupport.ManualTimeMeter;
import org.junit.jupiter.api.Test;

/** S-2.10 / D-12: MATRIX scope — 60 per minute per user, deterministic via a manual time meter. */
class MatrixRateLimitServiceTest {

  private final ManualTimeMeter time = new ManualTimeMeter();
  private final RateLimitProperties props = new RateLimitProperties();
  private final RateLimitService service = new RateLimitService(props, time);

  @Test
  void defaultIs60PerMinute() {
    assertThat(props.getMatrix().getCapacity()).isEqualTo(60);
    assertThat(props.getMatrix().getRefillPeriod()).isEqualTo(Duration.ofMinutes(1));
  }

  @Test
  void sixtiethAllowedSixtyFirstRefusedWithRetryAfter() {
    for (int i = 1; i <= 60; i++) {
      assertThat(service.tryConsume(RateLimitService.Scope.MATRIX, "u1").isConsumed())
          .as("request %d", i)
          .isTrue();
    }
    var refused = service.tryConsume(RateLimitService.Scope.MATRIX, "u1");
    assertThat(refused.isConsumed()).isFalse();
    assertThat(refused.getNanosToWaitForRefill()).isPositive();
  }

  @Test
  void newWindowAllowsAgain() {
    for (int i = 0; i < 60; i++) service.tryConsume(RateLimitService.Scope.MATRIX, "u1");
    assertThat(service.tryConsume(RateLimitService.Scope.MATRIX, "u1").isConsumed()).isFalse();
    time.advance(Duration.ofMinutes(1));
    for (int i = 0; i < 60; i++) {
      assertThat(service.tryConsume(RateLimitService.Scope.MATRIX, "u1").isConsumed()).isTrue();
    }
    assertThat(service.tryConsume(RateLimitService.Scope.MATRIX, "u1").isConsumed()).isFalse();
  }

  @Test
  void usersAreIsolatedAndScopeIsIsolated() {
    for (int i = 0; i < 60; i++) service.tryConsume(RateLimitService.Scope.MATRIX, "u1");
    assertThat(service.tryConsume(RateLimitService.Scope.MATRIX, "u1").isConsumed()).isFalse();
    assertThat(service.tryConsume(RateLimitService.Scope.MATRIX, "u2").isConsumed()).isTrue();
    assertThat(service.tryConsume(RateLimitService.Scope.AUTH, "u1").isConsumed()).isTrue();
  }

  @Test
  void idleBucketsAreEvictedSoMapDoesNotGrowUnbounded() {
    for (int i = 0; i < 100; i++) {
      service.tryConsume(RateLimitService.Scope.MATRIX, "user-" + i);
    }
    assertThat(service.estimatedBucketCount(RateLimitService.Scope.MATRIX)).isEqualTo(100);
    time.advance(Duration.ofMinutes(3)); // > 2x refill period
    assertThat(service.estimatedBucketCount(RateLimitService.Scope.MATRIX)).isZero();
  }
}
