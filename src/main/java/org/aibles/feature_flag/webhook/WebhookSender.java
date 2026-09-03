package org.aibles.feature_flag.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aibles.feature_flag.config.WebhookProperties;
import org.aibles.feature_flag.domain.entity.WebhookDeliveryAttempt;
import org.aibles.feature_flag.domain.entity.WebhookSubscription;
import org.aibles.feature_flag.exception.WebhookUrlNotAllowedException;
import org.aibles.feature_flag.repository.WebhookDeliveryAttemptRepository;
import org.aibles.feature_flag.util.SecretCipher;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Delivers one payload to one subscription, with retries (issue #36).
 *
 * <p>Retries are a plain loop with {@code Thread.sleep} rather than {@code spring-retry}: the
 * caller is already on an {@code @Async} thread whose only job is this delivery, so blocking it is
 * exactly the intended behaviour, and it avoids adding a dependency (the same reasoning that led
 * issue #30 to raw Caffeine over the Spring Cache abstraction).
 *
 * <p>Every attempt is persisted before the next one, so the delivery history survives a crash
 * mid-retry.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class WebhookSender {

  private final WebhookProperties properties;
  private final WebhookSigner signer;
  private final SsrfGuard ssrfGuard;
  private final SecretCipher secretCipher;
  private final WebhookDeliveryAttemptRepository attemptRepository;
  private final RestClient webhookRestClient;
  private final ObjectMapper webhookObjectMapper;

  /**
   * Attempts delivery up to {@code app.webhook.max-attempts} times, doubling the wait each failure.
   *
   * <p>Fail-safe: never throws. A subscriber's broken endpoint must not surface as an error in the
   * admin request that triggered the event — the request has already committed by this point.
   */
  public void deliver(WebhookSubscription subscription, WebhookPayload payload) {
    String body;
    try {
      body = webhookObjectMapper.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      log.error(
          "Webhook payload could not be serialized for subscription {}: {}",
          subscription.getId(),
          e.getClass().getSimpleName());
      return;
    }

    String secret;
    try {
      secret = secretCipher.decrypt(subscription.getSecretCiphertext());
    } catch (RuntimeException e) {
      // A wrong app.webhook.encryption-key makes every delivery unsignable. Log once per
      // delivery with the subscription id so the operator can see which rows are affected.
      log.error(
          "Webhook secret for subscription {} could not be decrypted — check"
              + " app.webhook.encryption-key",
          subscription.getId());
      return;
    }

    long backoffMillis = properties.initialBackoff().toMillis();
    for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
      Outcome outcome = attemptDelivery(subscription, payload, body, secret, attempt);
      if (outcome == Outcome.SUCCESS) {
        return;
      }
      if (outcome == Outcome.PERMANENT) {
        // Retrying a permanent rejection just burns attempts and delays nothing useful.
        log.warn(
            "Webhook delivery to subscription {} rejected permanently; not retrying",
            subscription.getId());
        return;
      }
      if (attempt < properties.maxAttempts()) {
        if (!sleep(backoffMillis)) {
          return; // interrupted — stop retrying and let the thread wind down
        }
        backoffMillis *= 2; // exponential: 1s, 2s, 4s, ...
      }
    }
    log.warn(
        "Webhook delivery to subscription {} failed after {} attempts",
        subscription.getId(),
        properties.maxAttempts());
  }

  /**
   * Whether the attempt succeeded, is worth retrying, or failed for a reason retrying cannot fix.
   */
  private enum Outcome {
    SUCCESS,
    RETRYABLE,
    PERMANENT
  }

  private Outcome attemptDelivery(
      WebhookSubscription subscription,
      WebhookPayload payload,
      String body,
      String secret,
      int attempt) {
    long startedAt = System.nanoTime();
    try {
      // Re-check on every attempt: DNS may have changed since the subscription was created,
      // so a subscribe-time-only check would be bypassable by DNS rebinding.
      ssrfGuard.verifyAllowed(subscription.getUrl());

      // Each attempt is signed with a fresh timestamp so a retry does not fall outside the
      // receiver's freshness window. The delivery id inside the body stays the same, which is
      // what lets a receiver dedupe retries.
      long timestamp = Instant.now().getEpochSecond();
      webhookRestClient
          .post()
          .uri(subscription.getUrl())
          .contentType(MediaType.APPLICATION_JSON)
          .header(WebhookSigner.TIMESTAMP_HEADER, Long.toString(timestamp))
          .header(WebhookSigner.EVENT_HEADER, payload.event().name())
          .header(WebhookSigner.DELIVERY_HEADER, payload.deliveryId())
          .header(WebhookSigner.SIGNATURE_HEADER, signer.sign(secret, timestamp, body))
          // Sign and send the same bytes: passing the serialized String (not the object)
          // guarantees the receiver digests exactly what was signed.
          .body(body)
          .retrieve()
          .toBodilessEntity();

      record(subscription, payload, attempt, true, null, null, startedAt);
      return Outcome.SUCCESS;
    } catch (WebhookUrlNotAllowedException e) {
      // The URL is blocked (or now resolves somewhere blocked). Retrying cannot change that.
      record(subscription, payload, attempt, false, null, e.getClass().getSimpleName(), startedAt);
      return Outcome.PERMANENT;
    } catch (RestClientResponseException e) {
      // The endpoint answered with 4xx/5xx — status is safe to record.
      int status = e.getStatusCode().value();
      record(
          subscription, payload, attempt, false, status, e.getClass().getSimpleName(), startedAt);
      return isRetryableStatus(status) ? Outcome.RETRYABLE : Outcome.PERMANENT;
    } catch (RuntimeException e) {
      // Connection/DNS failures: record the exception CLASS only. getMessage() embeds the
      // target URL, and a webhook URL can itself carry a token. Worth retrying — these are
      // typically transient.
      record(subscription, payload, attempt, false, null, e.getClass().getSimpleName(), startedAt);
      return Outcome.RETRYABLE;
    }
  }

  /**
   * 5xx is the server's problem and may clear; a 4xx means the request itself is wrong, so
   * replaying it unchanged will fail identically. The two exceptions are 408 (Request Timeout) and
   * 429 (Too Many Requests), which explicitly invite a retry.
   */
  private static boolean isRetryableStatus(int status) {
    return status >= 500 || status == 408 || status == 429;
  }

  private void record(
      WebhookSubscription subscription,
      WebhookPayload payload,
      int attempt,
      boolean succeeded,
      Integer status,
      String error,
      long startedAt) {
    long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
    try {
      attemptRepository.save(
          WebhookDeliveryAttempt.builder()
              .subscriptionId(subscription.getId())
              .eventType(payload.event())
              .attempt(attempt)
              .succeeded(succeeded)
              .responseStatus(status)
              .error(error)
              .durationMs(durationMs)
              .build());
    } catch (RuntimeException e) {
      // Logging the attempt must never break the delivery loop.
      log.warn(
          "Could not record webhook delivery attempt for subscription {}: {}",
          subscription.getId(),
          e.getClass().getSimpleName());
    }
  }

  /**
   * @return false if the thread was interrupted while waiting
   */
  private static boolean sleep(long millis) {
    try {
      Thread.sleep(millis);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
