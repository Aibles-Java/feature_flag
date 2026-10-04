package org.aibles.feature_flag.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class FlagStateResponse {
  private UUID flagId;
  private UUID environmentId;
  private boolean enabled;
  private String value;
  private int rolloutPercent;

  /** Optimistic-lock version of this state (ADR-07); the client echoes it on update (S-2.2). */
  private Long version;

  /**
   * Last SDK evaluation of this flag in this environment; null means never (issue #37). Admin-only
   * — the SDK's {@code FlagEvaluationResponse} is deliberately unchanged.
   */
  private LocalDateTime lastEvaluatedAt;
}
