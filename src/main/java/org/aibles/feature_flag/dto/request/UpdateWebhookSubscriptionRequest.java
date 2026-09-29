package org.aibles.feature_flag.dto.request;

import jakarta.validation.constraints.Size;
import java.util.Set;
import lombok.Data;
import org.aibles.feature_flag.domain.enums.WebhookEventType;

/** Every field is optional; a null field leaves that part of the subscription unchanged. */
@Data
public class UpdateWebhookSubscriptionRequest {

  @Size(max = 2048)
  private String url;

  private Set<WebhookEventType> eventTypes;

  private Boolean enabled;

  /**
   * There is deliberately no {@code secret} field. Rotating the shared secret is a separate
   * endpoint ({@code POST .../secret/rotate}) so the new value can be returned exactly once, the
   * same shape as environment API-key rotation.
   */
}
