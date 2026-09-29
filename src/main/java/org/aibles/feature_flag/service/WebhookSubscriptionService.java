package org.aibles.feature_flag.service;

import java.util.UUID;
import org.aibles.feature_flag.dto.request.CreateWebhookSubscriptionRequest;
import org.aibles.feature_flag.dto.request.UpdateWebhookSubscriptionRequest;
import org.aibles.feature_flag.dto.response.WebhookDeliveryAttemptResponse;
import org.aibles.feature_flag.dto.response.WebhookSubscriptionResponse;
import org.aibles.feature_flag.dto.response.WebhookSubscriptionSecretResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface WebhookSubscriptionService {

  /**
   * Returns the plaintext secret exactly once — it is stored encrypted and never returned again.
   */
  WebhookSubscriptionSecretResponse create(CreateWebhookSubscriptionRequest request);

  Page<WebhookSubscriptionResponse> listByEnvironment(UUID environmentId, Pageable pageable);

  WebhookSubscriptionResponse get(UUID id);

  WebhookSubscriptionResponse update(UUID id, UpdateWebhookSubscriptionRequest request);

  void delete(UUID id);

  /** Issues a fresh secret, invalidating the old one. Returned once, like create. */
  WebhookSubscriptionSecretResponse rotateSecret(UUID id);

  Page<WebhookDeliveryAttemptResponse> listDeliveryAttempts(UUID id, Pageable pageable);
}
