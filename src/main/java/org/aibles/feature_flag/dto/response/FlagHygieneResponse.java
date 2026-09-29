package org.aibles.feature_flag.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

/**
 * One row of the flag-hygiene report (issue #37).
 *
 * <p>A row is a <strong>(flag, environment) pair</strong>, not a flag. Staleness is inherently
 * per-environment — "unused in production" and "unused in dev" are different facts, and only the
 * first is a reason to delete anything. Expiry is a flag-level property, so an expired flag with
 * three environments produces three rows; that is deliberate, because each row also carries {@code
 * enabled}, which is how you spot the case that actually matters: an expired flag still switched on
 * in production.
 */
@Data
@Builder
public class FlagHygieneResponse {
  private UUID flagId;
  private String flagKey;
  private String flagName;
  private UUID environmentId;
  private String environmentName;

  /** The flag's configured state in this environment — not a per-identifier evaluation. */
  private boolean enabled;

  /** Null means never evaluated in this environment. */
  private LocalDateTime lastEvaluatedAt;

  /** Days since the last evaluation; null when never evaluated. */
  private Long daysSinceLastEvaluation;

  private boolean stale;
  private LocalDateTime expiresAt;
  private boolean expired;
}
