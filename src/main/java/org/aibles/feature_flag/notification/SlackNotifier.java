package org.aibles.feature_flag.notification;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Sends a message to Slack via an incoming webhook. Fail-safe: any error is logged and swallowed,
 * so a Slack outage can never break the calling flow.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SlackNotifier {

  private final SlackProperties properties;
  private final RestClient slackRestClient;

  public void send(String text) {
    if (!properties.isEnabled()
        || properties.getWebhookUrl() == null
        || properties.getWebhookUrl().isBlank()) {
      return;
    }
    try {
      slackRestClient
          .post()
          .uri(properties.getWebhookUrl())
          .contentType(MediaType.APPLICATION_JSON)
          .body(Map.of("text", text))
          .retrieve()
          .toBodilessEntity();
    } catch (Exception e) {
      // Log only the exception type: connection-level failures put the full webhook URL
      // (a bearer secret) into getMessage(), which must never reach the logs.
      log.warn("Slack notification failed: {}", e.getClass().getSimpleName());
    }
  }
}
