package org.aibles.feature_flag.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

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
            "checkout-v2", "staging", "web", false, true, "off", "on", "dev@example.com"));

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
            "checkout-v2", "Production", "web", true, false, "on", "off", "dev@example.com"));

    assertThat(capture()).startsWith("🔴");
  }

  @Test
  void apiKeyRotated_nonProduction_includesEnvProjectActor_noKey() {
    SlackEventListener listener = new SlackEventListener(slackNotifier);

    listener.onApiKeyRotated(new ApiKeyRotatedEvent("staging", "web", "dev@example.com"));

    String msg = capture();
    assertThat(msg).contains("staging").contains("web").contains("dev@example.com");
    assertThat(msg).doesNotContain("🔴");
  }

  @Test
  void apiKeyRotated_production_usesCriticalSeverity() {
    SlackEventListener listener = new SlackEventListener(slackNotifier);

    listener.onApiKeyRotated(new ApiKeyRotatedEvent("production", "web", "dev@example.com"));

    assertThat(capture()).startsWith("🔴");
  }

  @Test
  void flagArchived_archived_saysArchived() {
    SlackEventListener listener = new SlackEventListener(slackNotifier);

    listener.onFlagArchived(new FlagArchivedEvent("old-flag", "web", true, "dev@example.com"));

    String msg = capture();
    assertThat(msg).contains("old-flag").contains("web").contains("archived");
    assertThat(msg).doesNotContain("unarchived");
  }

  @Test
  void flagArchived_unarchived_saysUnarchived() {
    SlackEventListener listener = new SlackEventListener(slackNotifier);

    listener.onFlagArchived(new FlagArchivedEvent("old-flag", "web", false, "dev@example.com"));

    assertThat(capture()).contains("unarchived");
  }
}
