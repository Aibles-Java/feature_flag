package org.aibles.feature_flag.config;

import jakarta.validation.constraints.AssertTrue;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Flag-hygiene configuration, bound from {@code app.hygiene.*} (issue #37).
 *
 * @param staleAfter how long without an evaluation before a (flag, environment) pair is reported
 *     stale. Reporting only — nothing is disabled or deleted on this basis.
 * @param evaluationTouchThrottle the minimum gap between {@code last_evaluated_at} writes for the
 *     same key. This is the knob that keeps usage tracking off the SDK hot path: with the default,
 *     an environment served 10,000 evaluations a minute produces at most one UPDATE per window
 *     instead of 10,000.
 */
@ConfigurationProperties(prefix = "app.hygiene")
@Validated
public record HygieneProperties(Duration staleAfter, Duration evaluationTouchThrottle) {

  public HygieneProperties {
    staleAfter = staleAfter == null ? Duration.ofDays(30) : staleAfter;
    evaluationTouchThrottle =
        evaluationTouchThrottle == null ? Duration.ofMinutes(5) : evaluationTouchThrottle;
  }

  @AssertTrue(message = "app.hygiene.stale-after must be a positive duration")
  public boolean isStaleAfterPositive() {
    return !staleAfter.isNegative() && !staleAfter.isZero();
  }

  /**
   * Zero would mean "write on every evaluation", which is precisely the performance problem the
   * throttle exists to prevent — so it is rejected rather than silently accepted.
   */
  @AssertTrue(message = "app.hygiene.evaluation-touch-throttle must be a positive duration")
  public boolean isThrottlePositive() {
    return !evaluationTouchThrottle.isNegative() && !evaluationTouchThrottle.isZero();
  }
}
