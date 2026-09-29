package org.aibles.feature_flag.webhook;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.aibles.feature_flag.config.WebhookProperties;
import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.domain.entity.WebhookSubscription;
import org.aibles.feature_flag.domain.enums.FlagValueType;
import org.aibles.feature_flag.domain.enums.WebhookEventType;
import org.aibles.feature_flag.notification.event.ApiKeyRotatedEvent;
import org.aibles.feature_flag.notification.event.FlagArchivedEvent;
import org.aibles.feature_flag.notification.event.FlagCreatedEvent;
import org.aibles.feature_flag.notification.event.FlagStateChangedEvent;
import org.aibles.feature_flag.notification.event.FlagUpdatedEvent;
import org.aibles.feature_flag.repository.EnvironmentRepository;
import org.aibles.feature_flag.repository.WebhookSubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WebhookDispatcherTest {

  @Mock WebhookSubscriptionRepository subscriptionRepository;
  @Mock EnvironmentRepository environmentRepository;
  @Mock WebhookSender sender;

  UUID envId = UUID.randomUUID();
  UUID otherEnvId = UUID.randomUUID();
  UUID projectId = UUID.randomUUID();

  WebhookDispatcher dispatcher;

  private static WebhookProperties props(boolean enabled) {
    return new WebhookProperties(
        enabled,
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        3,
        Duration.ofMillis(1),
        Duration.ofSeconds(1),
        Duration.ofSeconds(1),
        true);
  }

  private WebhookSubscription subscription(UUID environmentId, WebhookEventType... types) {
    return WebhookSubscription.builder()
        .id(UUID.randomUUID())
        .environmentId(environmentId)
        .url("https://example.com/hook")
        .secretCiphertext("cipher")
        .enabled(true)
        .eventTypes(EnumSet.copyOf(List.of(types)))
        .build();
  }

  @BeforeEach
  void setUp() {
    dispatcher =
        new WebhookDispatcher(props(true), subscriptionRepository, environmentRepository, sender);
  }

  private FlagStateChangedEvent stateChange() {
    return new FlagStateChangedEvent(
        envId, "checkout", "production", "web", false, true, "off", "on", "dev@example.com");
  }

  @Test
  void deliversEnvironmentScopedEventToThatEnvironmentOnly() {
    WebhookSubscription sub = subscription(envId, WebhookEventType.FLAG_STATE_CHANGED);
    when(subscriptionRepository.findAllByEnvironmentIdAndEnabledTrue(envId))
        .thenReturn(List.of(sub));

    dispatcher.onFlagStateChanged(stateChange());

    verify(sender).deliver(eq(sub), any());
    verify(environmentRepository, never()).findAllByProjectId(any());
  }

  @Test
  @DisplayName("the payload carries the event type and the environment it is scoped to")
  void payloadIdentifiesEventAndEnvironment() {
    WebhookSubscription sub = subscription(envId, WebhookEventType.FLAG_STATE_CHANGED);
    when(subscriptionRepository.findAllByEnvironmentIdAndEnabledTrue(envId))
        .thenReturn(List.of(sub));

    dispatcher.onFlagStateChanged(stateChange());

    verify(sender)
        .deliver(
            eq(sub),
            org.mockito.ArgumentMatchers.argThat(
                payload ->
                    payload.event() == WebhookEventType.FLAG_STATE_CHANGED
                        && payload.environmentId().equals(envId.toString())
                        && "checkout".equals(payload.data().get("flagKey"))
                        && Boolean.TRUE.equals(payload.data().get("enabled"))));
  }

  @Test
  @DisplayName("a project-scoped flag event fans out to every environment in the project")
  void fansOutProjectScopedEvents() {
    when(environmentRepository.findAllByProjectId(projectId))
        .thenReturn(
            List.of(
                Environment.builder().id(envId).build(),
                Environment.builder().id(otherEnvId).build()));
    WebhookSubscription first = subscription(envId, WebhookEventType.FLAG_CREATED);
    WebhookSubscription second = subscription(otherEnvId, WebhookEventType.FLAG_CREATED);
    when(subscriptionRepository.findAllByEnvironmentIdAndEnabledTrue(envId))
        .thenReturn(List.of(first));
    when(subscriptionRepository.findAllByEnvironmentIdAndEnabledTrue(otherEnvId))
        .thenReturn(List.of(second));

    dispatcher.onFlagCreated(
        new FlagCreatedEvent(
            projectId, "new-flag", "New Flag", FlagValueType.BOOLEAN, "dev@example.com"));

    verify(sender).deliver(eq(first), any());
    verify(sender).deliver(eq(second), any());
  }

  @Test
  void skipsSubscriptionsNotListeningForTheEvent() {
    WebhookSubscription sub = subscription(envId, WebhookEventType.API_KEY_ROTATED);
    when(subscriptionRepository.findAllByEnvironmentIdAndEnabledTrue(envId))
        .thenReturn(List.of(sub));

    dispatcher.onFlagStateChanged(stateChange());

    verifyNoInteractions(sender);
  }

  @Test
  void skipsDisabledSubscriptions() {
    WebhookSubscription sub = subscription(envId, WebhookEventType.FLAG_STATE_CHANGED);
    sub.setEnabled(false);
    when(subscriptionRepository.findAllByEnvironmentIdAndEnabledTrue(envId))
        .thenReturn(List.of(sub));

    dispatcher.onFlagStateChanged(stateChange());

    verifyNoInteractions(sender);
  }

  @Test
  @DisplayName("app.webhook.enabled=false short-circuits before any DB read")
  void doesNothingWhenGloballyDisabled() {
    dispatcher =
        new WebhookDispatcher(props(false), subscriptionRepository, environmentRepository, sender);

    dispatcher.onFlagStateChanged(stateChange());
    dispatcher.onFlagCreated(
        new FlagCreatedEvent(projectId, "k", "n", FlagValueType.BOOLEAN, "dev@example.com"));

    verifyNoInteractions(sender, subscriptionRepository, environmentRepository);
  }

  @Test
  @DisplayName("one failing subscription does not stop the others")
  void continuesAfterASubscriptionFails() {
    WebhookSubscription bad = subscription(envId, WebhookEventType.FLAG_STATE_CHANGED);
    WebhookSubscription good = subscription(envId, WebhookEventType.FLAG_STATE_CHANGED);
    when(subscriptionRepository.findAllByEnvironmentIdAndEnabledTrue(envId))
        .thenReturn(List.of(bad, good));
    org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(sender).deliver(eq(bad), any());

    dispatcher.onFlagStateChanged(stateChange());

    verify(sender).deliver(eq(good), any());
  }

  @Test
  @DisplayName("the API-key-rotated payload never carries a key or hash")
  void apiKeyRotatedPayloadCarriesNoSecret() {
    WebhookSubscription sub = subscription(envId, WebhookEventType.API_KEY_ROTATED);
    when(subscriptionRepository.findAllByEnvironmentIdAndEnabledTrue(envId))
        .thenReturn(List.of(sub));

    dispatcher.onApiKeyRotated(
        new ApiKeyRotatedEvent(envId, "production", "web", "dev@example.com"));

    verify(sender)
        .deliver(
            eq(sub),
            org.mockito.ArgumentMatchers.argThat(
                payload ->
                    !payload.data().containsKey("apiKey")
                        && !payload.data().containsKey("apiKeyHash")
                        && !payload.data().containsKey("secret")));
  }

  @Test
  void deliversFlagUpdatedAndArchivedAsProjectScoped() {
    when(environmentRepository.findAllByProjectId(projectId))
        .thenReturn(List.of(Environment.builder().id(envId).build()));
    WebhookSubscription sub =
        subscription(envId, WebhookEventType.FLAG_UPDATED, WebhookEventType.FLAG_ARCHIVED);
    when(subscriptionRepository.findAllByEnvironmentIdAndEnabledTrue(envId))
        .thenReturn(List.of(sub));

    dispatcher.onFlagUpdated(
        new FlagUpdatedEvent(projectId, "k", "New name", "desc", "dev@example.com"));
    dispatcher.onFlagArchived(
        new FlagArchivedEvent(projectId, "k", "web", true, "dev@example.com"));

    verify(sender, times(2)).deliver(eq(sub), any());
  }

  @Test
  @DisplayName("a null scope id is ignored rather than throwing on the async thread")
  void ignoresNullScopeIds() {
    dispatcher.onFlagStateChanged(
        new FlagStateChangedEvent(
            null, "k", "production", "web", false, true, "off", "on", "dev@example.com"));
    dispatcher.onFlagCreated(
        new FlagCreatedEvent(null, "k", "n", FlagValueType.BOOLEAN, "dev@example.com"));

    verifyNoInteractions(sender);
  }
}
