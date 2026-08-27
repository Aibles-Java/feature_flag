package org.aibles.feature_flag.dto.response;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;
import org.aibles.feature_flag.domain.enums.WebhookEventType;

/**
 * A webhook subscription as returned by every read endpoint. Carries <strong>no</strong> secret —
 * neither the plaintext nor the ciphertext. See {@link WebhookSubscriptionSecretResponse} for the
 * one-time reveal on create/rotate.
 */
@Data
@Builder
public class WebhookSubscriptionResponse {
  private UUID id;
  private UUID environmentId;
  private String url;
  private Set<WebhookEventType> eventTypes;
  private boolean enabled;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
