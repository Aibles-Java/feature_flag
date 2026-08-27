package org.aibles.feature_flag.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Set;
import java.util.UUID;
import lombok.Data;
import org.aibles.feature_flag.domain.enums.WebhookEventType;

@Data
public class CreateWebhookSubscriptionRequest {

  @NotNull private UUID environmentId;

  /**
   * The endpoint to POST to. Format is checked by {@code SsrfGuard} rather than a
   * regex/{@code @URL} constraint, because the real requirement is "resolves to a public address",
   * which needs DNS.
   */
  @NotNull
  @Size(max = 2048)
  private String url;

  /** At least one event type — a subscription listening for nothing would never deliver. */
  @NotEmpty private Set<WebhookEventType> eventTypes;

  /** Optional. When absent the server generates a 256-bit secret and returns it once. */
  @Size(min = 16, max = 256)
  private String secret;
}
