package org.aibles.feature_flag.security.ratelimit;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the API rate limiter (issue #26), bound from {@code app.rate-limit.*}. All
 * values are runtime-configurable via properties — no code change needed to tune limits.
 *
 * <p>Each {@link Limit} models a token bucket: {@code capacity} tokens that refill fully over
 * {@code refill-period} (e.g. {@code capacity=10, refill-period=1m} ≈ "10 requests per minute").
 */
@Data
@ConfigurationProperties(prefix = "app.rate-limit")
public class RateLimitProperties {

  /** Master switch. Disabled in the test profile so unrelated tests aren't throttled. */
  private boolean enabled = true;

  /** Per-IP limit for {@code /api/v1/auth/**} (brute-force protection). */
  private Limit auth = new Limit(10, Duration.ofMinutes(1));

  /** Per-API-key limit for {@code /api/v1/sdk/**} (abuse protection). */
  private Limit sdk = new Limit(300, Duration.ofMinutes(1));

  /**
   * Per-IP limit for {@code /api/v1/sdk/**}, applied <em>before</em> the API key is authenticated —
   * the only ceiling on anonymous key-probing, since {@link #sdk} can only bucket a request that
   * already authenticated. Deliberately looser than {@link #sdk} so that many clients sharing one
   * egress IP are not throttled ahead of the per-key limit.
   */
  private Limit sdkIp = new Limit(600, Duration.ofMinutes(1));

  /**
   * Per-user limit for {@code GET /api/v1/flags/environment-states} (S-2.10, D-12): 60 requests per
   * minute by default; the response can be large (up to ~16 MB at 100 flags x 20 envs), so this
   * caps per-user bandwidth/DB amplification. Override with {@code APP_RATELIMIT_MATRIX_CAPACITY} /
   * {@code APP_RATELIMIT_MATRIX_REFILLPERIOD}.
   */
  private Limit matrix = new Limit(60, Duration.ofMinutes(1));

  @Data
  public static class Limit {
    /** Max tokens in the bucket (burst size). */
    private long capacity;

    /** Time to refill the full capacity. */
    private Duration refillPeriod;

    public Limit() {}

    public Limit(long capacity, Duration refillPeriod) {
      this.capacity = capacity;
      this.refillPeriod = refillPeriod;
    }
  }
}
