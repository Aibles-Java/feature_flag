package org.aibles.feature_flag.dto.response;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;
import org.aibles.feature_flag.domain.enums.WebhookEventType;

/**
 * Returned <strong>only</strong> on subscription creation and secret rotation. Carries the
 * plaintext {@code secret} exactly once — it is stored encrypted and never returned again, so the
 * caller must capture it now to verify signatures. All read endpoints return the secret-free {@link
 * WebhookSubscriptionResponse}.
 *
 * <p>Mirrors {@link EnvironmentSecretResponse}, which does the same for SDK API keys.
 */
@Data
@Builder
public class WebhookSubscriptionSecretResponse {
  private UUID id;
  private UUID environmentId;
  private String url;
  private Set<WebhookEventType> eventTypes;
  private boolean enabled;
  private String secret;
  private LocalDateTime createdAt;
}
