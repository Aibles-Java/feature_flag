package org.aibles.feature_flag.security.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RateLimitService} bucket behavior: a key gets exactly {@code capacity}
 * tokens, the next request is refused, and distinct keys/scopes have independent buckets.
 */
class RateLimitServiceTest {

  private RateLimitService serviceWithCapacity(int authCapacity, int sdkCapacity) {
    RateLimitProperties props = new RateLimitProperties();
    props.setEnabled(true);
    props.setAuth(new RateLimitProperties.Limit(authCapacity, Duration.ofMinutes(1)));
    props.setSdk(new RateLimitProperties.Limit(sdkCapacity, Duration.ofMinutes(1)));
    return new RateLimitService(props);
  }

  @Test
  void allowsUpToCapacityThenRefuses() {
    RateLimitService service = serviceWithCapacity(2, 2);

    assertThat(service.tryConsume(RateLimitService.Scope.AUTH, "1.2.3.4").isConsumed()).isTrue();
    assertThat(service.tryConsume(RateLimitService.Scope.AUTH, "1.2.3.4").isConsumed()).isTrue();
    // Third request within the window is over the limit.
    assertThat(service.tryConsume(RateLimitService.Scope.AUTH, "1.2.3.4").isConsumed()).isFalse();
  }

  @Test
  void keysAreIsolated() {
    RateLimitService service = serviceWithCapacity(1, 1);

    assertThat(service.tryConsume(RateLimitService.Scope.AUTH, "ip-a").isConsumed()).isTrue();
    // A different IP has its own fresh bucket.
    assertThat(service.tryConsume(RateLimitService.Scope.AUTH, "ip-b").isConsumed()).isTrue();
    // Exhausted IP is refused.
    assertThat(service.tryConsume(RateLimitService.Scope.AUTH, "ip-a").isConsumed()).isFalse();
  }

  @Test
  void scopesAreIsolated() {
    RateLimitService service = serviceWithCapacity(1, 1);

    assertThat(service.tryConsume(RateLimitService.Scope.AUTH, "same-key").isConsumed()).isTrue();
    // Same key string, different scope → separate bucket.
    assertThat(service.tryConsume(RateLimitService.Scope.SDK, "same-key").isConsumed()).isTrue();
  }

  @Test
  void refusedProbeReportsRetryAfter() {
    RateLimitService service = serviceWithCapacity(1, 1);
    service.tryConsume(RateLimitService.Scope.SDK, "k");

    var probe = service.tryConsume(RateLimitService.Scope.SDK, "k");
    assertThat(probe.isConsumed()).isFalse();
    assertThat(probe.getNanosToWaitForRefill()).isPositive();
  }
}
