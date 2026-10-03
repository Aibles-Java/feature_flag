package org.aibles.feature_flag.apikey;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.aibles.feature_flag.config.ApiKeyProperties;
import org.aibles.feature_flag.config.WebhookProperties;
import org.aibles.feature_flag.notification.SlackProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class ApiKeyExpiryChannelCheckTest {

  private static final String WARNING = "keys will expire without notice";

  private static SlackProperties slack(boolean enabled, String url) {
    SlackProperties props = new SlackProperties();
    props.setEnabled(enabled);
    props.setWebhookUrl(url);
    return props;
  }

  private static WebhookProperties webhooks(boolean enabled) {
    return new WebhookProperties(
        enabled,
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        3,
        Duration.ofMillis(1),
        Duration.ofSeconds(1),
        Duration.ofSeconds(1),
        true);
  }

  private static ApiKeyProperties warnings(boolean enabled) {
    return new ApiKeyProperties(null, new ApiKeyProperties.ExpiryWarning(enabled, null, null));
  }

  @Test
  void warnsWhenWarningsAreOnButNoChannelIsActive(CapturedOutput output) {
    new ApiKeyExpiryChannelCheck(warnings(true), slack(false, null), webhooks(false))
        .checkChannels();

    assertThat(output).contains(WARNING);
  }

  @Test
  void staysQuietWhenSlackIsConfigured(CapturedOutput output) {
    new ApiKeyExpiryChannelCheck(
            warnings(true), slack(true, "https://hooks.slack.com/services/x"), webhooks(false))
        .checkChannels();

    assertThat(output).doesNotContain(WARNING);
  }

  @Test
  void staysQuietWhenWebhooksAreEnabled(CapturedOutput output) {
    new ApiKeyExpiryChannelCheck(warnings(true), slack(false, null), webhooks(true))
        .checkChannels();

    assertThat(output).doesNotContain(WARNING);
  }

  @Test
  void staysQuietWhenWarningsAreDisabled(CapturedOutput output) {
    new ApiKeyExpiryChannelCheck(warnings(false), slack(false, null), webhooks(false))
        .checkChannels();

    assertThat(output).doesNotContain(WARNING);
  }

  @Test
  void slackEnabledWithoutAUrlDoesNotCountAsActive() {
    assertThat(
            new ApiKeyExpiryChannelCheck(warnings(true), slack(true, " "), webhooks(false))
                .anyChannelActive())
        .isFalse();
  }
}
