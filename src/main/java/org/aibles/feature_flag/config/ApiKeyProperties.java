package org.aibles.feature_flag.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * SDK API key lifecycle configuration, bound from {@code app.api-key.*}.
 *
 * @param defaultTtl lifetime of a key created through {@code POST /api-keys} with neither {@code
 *     expiresAt} nor {@code neverExpires}. Environment creation and cloning do not apply it: those
 *     endpoints give the caller no way to choose a lifetime or opt out.
 * @param expiryWarning the daily scan that warns before a key expires
 */
@ConfigurationProperties(prefix = "app.api-key")
@Validated
public record ApiKeyProperties(Duration defaultTtl, @Valid ExpiryWarning expiryWarning) {

  public ApiKeyProperties {
    defaultTtl = defaultTtl == null ? Duration.ofDays(90) : defaultTtl;
    expiryWarning = expiryWarning == null ? new ExpiryWarning(null, null, null) : expiryWarning;
  }

  @AssertTrue(message = "app.api-key.default-ttl must be a positive duration")
  public boolean isDefaultTtlPositive() {
    return !defaultTtl.isNegative() && !defaultTtl.isZero();
  }

  /**
   * @param enabled master switch, {@code true} when absent
   * @param cron when the scan runs, in the server time zone
   * @param thresholdsDays remaining lifetimes, in days, at which a key is warned — once each
   */
  public record ExpiryWarning(Boolean enabled, String cron, List<Integer> thresholdsDays) {

    public ExpiryWarning {
      enabled = enabled == null ? Boolean.TRUE : enabled;
      cron = cron == null || cron.isBlank() ? "0 0 9 * * *" : cron;
      thresholdsDays =
          thresholdsDays == null || thresholdsDays.isEmpty()
              ? List.of(30, 7, 1)
              : List.copyOf(thresholdsDays);
    }

    @AssertTrue(message = "app.api-key.expiry-warning.thresholds-days must all be positive")
    public boolean isThresholdsPositive() {
      return thresholdsDays.stream().allMatch(days -> days > 0);
    }
  }
}
