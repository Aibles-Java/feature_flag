package org.aibles.feature_flag.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.UUID;
import org.aibles.feature_flag.notification.event.ApiKeyExpiringEvent;
import org.aibles.feature_flag.notification.event.ApiKeyRotatedEvent;
import org.aibles.feature_flag.notification.event.FlagArchivedEvent;
import org.aibles.feature_flag.notification.event.FlagStateChangedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SlackEventListenerTest {

  @Mock SlackNotifier slackNotifier;

  private String capture() {
    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(slackNotifier).send(captor.capture());
    return captor.getValue();
  }

  @Test
  void flagStateChanged_nonProduction_usesNormalSeverityAndIncludesDetails() {
    SlackEventListener listener = new SlackEventListener(slackNotifier);

    listener.onFlagStateChanged(
        new FlagStateChangedEvent(
            UUID.randomUUID(),
            "checkout-v2",
            "staging",
            "web",
            false,
            true,
            "off",
            "on",
            "dev@example.com"));

    String msg = capture();
    assertThat(msg).contains("checkout-v2").contains("staging").contains("web");
    assertThat(msg).contains("enabled=true").contains("value=on").contains("dev@example.com");
    assertThat(msg).doesNotContain("🔴");
  }

  @Test
  void flagStateChanged_production_usesCriticalSeverity() {
    SlackEventListener listener = new SlackEventListener(slackNotifier);

    listener.onFlagStateChanged(
        new FlagStateChangedEvent(
            UUID.randomUUID(),
            "checkout-v2",
            "Production",
            "web",
            true,
            false,
            "on",
            "off",
            "dev@example.com"));

    assertThat(capture()).startsWith("🔴");
  }

  @Test
  void apiKeyRotated_nonProduction_includesEnvProjectActor_noKey() {
    SlackEventListener listener = new SlackEventListener(slackNotifier);

    listener.onApiKeyRotated(
        new ApiKeyRotatedEvent(UUID.randomUUID(), "staging", "web", "dev@example.com"));

    String msg = capture();
    assertThat(msg).contains("staging").contains("web").contains("dev@example.com");
    assertThat(msg).doesNotContain("🔴");
  }

  @Test
  void apiKeyRotated_production_usesCriticalSeverity() {
    SlackEventListener listener = new SlackEventListener(slackNotifier);

    listener.onApiKeyRotated(
        new ApiKeyRotatedEvent(UUID.randomUUID(), "production", "web", "dev@example.com"));

    assertThat(capture()).startsWith("🔴");
  }

  @Test
  void flagArchived_archived_saysArchived() {
    SlackEventListener listener = new SlackEventListener(slackNotifier);

    listener.onFlagArchived(
        new FlagArchivedEvent(UUID.randomUUID(), "old-flag", "web", true, "dev@example.com"));

    String msg = capture();
    assertThat(msg).contains("old-flag").contains("web").contains("archived");
    assertThat(msg).doesNotContain("unarchived");
  }

  @Test
  void flagArchived_unarchived_saysUnarchived() {
    SlackEventListener listener = new SlackEventListener(slackNotifier);

    listener.onFlagArchived(
        new FlagArchivedEvent(UUID.randomUUID(), "old-flag", "web", false, "dev@example.com"));

    assertThat(capture()).contains("unarchived");
  }

  private static ApiKeyExpiringEvent expiring(
      String environmentName, LocalDateTime lastUsedAt, long daysLeft) {
    return new ApiKeyExpiringEvent(
        UUID.randomUUID(),
        environmentName,
        "checkout",
        UUID.randomUUID(),
        "nightly-batch",
        "a3f9c1d2",
        LocalDateTime.of(2026, 4, 1, 0, 0),
        lastUsedAt,
        daysLeft);
  }

  @Test
  void apiKeyExpiring_includesKeyDeadlineAndLastUse() {
    SlackEventListener listener = new SlackEventListener(slackNotifier);

    listener.onApiKeyExpiring(expiring("staging", LocalDateTime.of(2026, 3, 24, 2, 0), 7));

    String msg = capture();
    assertThat(msg).contains("nightly-batch").contains("a3f9c1d2").contains("staging");
    assertThat(msg).contains("checkout").contains("expires in 7 days").contains("2026-04-01 00:00");
    assertThat(msg).contains("Last used 2026-03-24 02:00");
    assertThat(msg).doesNotContain("🔴");
  }

  @Test
  void apiKeyExpiring_neverUsedKey_saysSoAndUsesSingularDay() {
    SlackEventListener listener = new SlackEventListener(slackNotifier);

    listener.onApiKeyExpiring(expiring("staging", null, 1));

    String msg = capture();
    assertThat(msg).contains("expires in 1 day (").contains("Never used");
  }

  @Test
  void apiKeyExpiring_production_usesCriticalSeverity() {
    SlackEventListener listener = new SlackEventListener(slackNotifier);

    listener.onApiKeyExpiring(expiring("production", null, 7));

    assertThat(capture()).startsWith("🔴");
  }
}
