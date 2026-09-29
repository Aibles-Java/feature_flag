package org.aibles.feature_flag.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;
import org.aibles.feature_flag.domain.enums.WebhookEventType;

/** One recorded delivery attempt, for debugging a failing endpoint. */
@Data
@Builder
public class WebhookDeliveryAttemptResponse {
  private UUID id;
  private UUID subscriptionId;
  private WebhookEventType eventType;
  private int attempt;
  private boolean succeeded;
  private Integer responseStatus;
  private String error;
  private Long durationMs;
  private LocalDateTime createdAt;
}
