package org.aibles.feature_flag.webhook;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aibles.feature_flag.config.WebhookProperties;
import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.domain.entity.WebhookSubscription;
import org.aibles.feature_flag.domain.enums.WebhookEventType;
import org.aibles.feature_flag.notification.event.ApiKeyRotatedEvent;
import org.aibles.feature_flag.notification.event.FlagArchivedEvent;
import org.aibles.feature_flag.notification.event.FlagCreatedEvent;
import org.aibles.feature_flag.notification.event.FlagStateChangedEvent;
import org.aibles.feature_flag.notification.event.FlagUpdatedEvent;
import org.aibles.feature_flag.repository.EnvironmentRepository;
import org.aibles.feature_flag.repository.WebhookSubscriptionRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Turns domain events into webhook deliveries (issue #36).
 *
 * <p>A second consumer of the same event pipeline {@code SlackEventListener} uses: {@code
 * AFTER_COMMIT} so a rolled-back mutation never notifies anyone, and {@code @Async} so a slow
 * subscriber endpoint never delays the admin request. The two listeners are independent — a Slack
 * outage cannot stop webhooks, and vice versa.
 *
 * <p><strong>Environment- vs project-scoped events.</strong> Subscriptions are per-environment, but
 * a flag belongs to a project. Flag create/update/archive therefore fan out to every environment in
 * the project (which mirrors the data model: creating a flag auto-creates one {@code
 * FlagEnvironmentState} per environment). Flag-state changes and key rotation already name a single
 * environment and go only there.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class WebhookDispatcher {

  private final WebhookProperties properties;
  private final WebhookSubscriptionRepository subscriptionRepository;
  private final EnvironmentRepository environmentRepository;
  private final WebhookSender sender;

  @Async
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onFlagStateChanged(FlagStateChangedEvent event) {
    Map<String, Object> data = new HashMap<>();
    data.put("flagKey", event.flagKey());
    data.put("environmentName", event.environmentName());
    data.put("projectName", event.projectName());
    data.put("previousEnabled", event.previousEnabled());
    data.put("enabled", event.newEnabled());
    data.put("previousValue", event.previousValue());
    data.put("value", event.newValue());
    data.put("actorEmail", event.actorEmail());

    dispatchToEnvironment(WebhookEventType.FLAG_STATE_CHANGED, event.environmentId(), data);
  }

  @Async
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onApiKeyRotated(ApiKeyRotatedEvent event) {
    Map<String, Object> data = new HashMap<>();
    data.put("environmentName", event.environmentName());
    data.put("projectName", event.projectName());
    data.put("actorEmail", event.actorEmail());
    // Deliberately no key/hash: the rotation event never carries the secret, and neither
    // does the webhook payload.

    dispatchToEnvironment(WebhookEventType.API_KEY_ROTATED, event.environmentId(), data);
  }

  @Async
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onFlagCreated(FlagCreatedEvent event) {
    Map<String, Object> data = new HashMap<>();
    data.put("flagKey", event.flagKey());
    data.put("flagName", event.flagName());
    data.put("valueType", event.valueType() == null ? null : event.valueType().name());
    data.put("actorEmail", event.actorEmail());

    dispatchToProject(WebhookEventType.FLAG_CREATED, event.projectId(), data);
  }

  @Async
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onFlagUpdated(FlagUpdatedEvent event) {
    Map<String, Object> data = new HashMap<>();
    data.put("flagKey", event.flagKey());
    data.put("flagName", event.flagName());
    data.put("description", event.description());
    data.put("actorEmail", event.actorEmail());

    dispatchToProject(WebhookEventType.FLAG_UPDATED, event.projectId(), data);
  }

  @Async
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onFlagArchived(FlagArchivedEvent event) {
    Map<String, Object> data = new HashMap<>();
    data.put("flagKey", event.flagKey());
    data.put("projectName", event.projectName());
    data.put("archived", event.archived());
    data.put("actorEmail", event.actorEmail());

    dispatchToProject(WebhookEventType.FLAG_ARCHIVED, event.projectId(), data);
  }

  /** Delivers to the subscriptions of a single environment. */
  private void dispatchToEnvironment(
      WebhookEventType eventType, UUID environmentId, Map<String, Object> data) {
    if (!properties.enabled() || environmentId == null) {
      return;
    }
    deliverAll(eventType, environmentId, data);
  }

  /** Fans out to every environment in the project — see the class-level note. */
  private void dispatchToProject(
      WebhookEventType eventType, UUID projectId, Map<String, Object> data) {
    if (!properties.enabled() || projectId == null) {
      return;
    }
    for (Environment environment : environmentRepository.findAllByProjectId(projectId)) {
      deliverAll(eventType, environment.getId(), data);
    }
  }

  private void deliverAll(
      WebhookEventType eventType, UUID environmentId, Map<String, Object> data) {
    List<WebhookSubscription> subscriptions =
        subscriptionRepository.findAllByEnvironmentIdAndEnabledTrue(environmentId);
    if (subscriptions.isEmpty()) {
      return;
    }
    WebhookPayload payload =
        new WebhookPayload(
            eventType, Instant.now().getEpochSecond(), environmentId.toString(), data);

    for (WebhookSubscription subscription : subscriptions) {
      if (!subscription.listensFor(eventType)) {
        continue;
      }
      try {
        sender.deliver(subscription, payload);
      } catch (RuntimeException e) {
        // One broken subscription must not stop the others.
        log.warn(
            "Webhook dispatch to subscription {} failed unexpectedly: {}",
            subscription.getId(),
            e.getClass().getSimpleName());
      }
    }
  }
}
