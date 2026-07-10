package org.aibles.feature_flag.notification;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for in-app Slack notifications, bound from {@code app.slack.*}. Disabled by
 * default; enable and supply a webhook URL to receive flag/environment change notifications.
 */
@Data
@ConfigurationProperties(prefix = "app.slack")
public class SlackProperties {

  /** Master switch. Disabled by default so no HTTP is attempted unless explicitly turned on. */
  private boolean enabled = false;

  /** Slack incoming-webhook URL. When null/blank, notifications are skipped. */
  private String webhookUrl;
}
