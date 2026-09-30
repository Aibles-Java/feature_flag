package org.aibles.feature_flag.apikey;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aibles.feature_flag.config.ApiKeyProperties;
import org.aibles.feature_flag.config.WebhookProperties;
import org.aibles.feature_flag.notification.SlackProperties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Makes a silent warning pipeline loud. {@code SlackNotifier} and {@code WebhookDispatcher} both
 * return quietly when disabled — right for "a flag was toggled", wrong here: key expiry is enforced
 * with certainty, so a warning that can reach no one means keys die unannounced. Logs once at
 * startup; never blocks it.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ApiKeyExpiryChannelCheck {

  private final ApiKeyProperties apiKeyProperties;
  private final SlackProperties slackProperties;
  private final WebhookProperties webhookProperties;

  @EventListener(ApplicationReadyEvent.class)
  public void checkChannels() {
    if (apiKeyProperties.expiryWarning().enabled() && !anyChannelActive()) {
      log.warn(
          "API key expiry warnings are enabled but no notification channel is active — keys will"
              + " expire without notice. Enable app.slack (with a webhook URL) or app.webhook.");
    }
  }

  boolean anyChannelActive() {
    String url = slackProperties.getWebhookUrl();
    boolean slackActive = slackProperties.isEnabled() && url != null && !url.isBlank();
    return slackActive || webhookProperties.enabled();
  }
}
