package org.aibles.feature_flag.config;

import org.aibles.feature_flag.notification.SlackProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.client.RestClient;

/**
 * Wiring for the event-driven, async Slack notification pipeline. {@link EnableAsync} activates the
 * {@code @Async} listener threads; Slack calls never block or roll back business transactions.
 */
@Configuration
@EnableAsync
@EnableConfigurationProperties(SlackProperties.class)
public class NotificationConfig {

  @Bean
  public RestClient slackRestClient() {
    return RestClient.create();
  }
}
