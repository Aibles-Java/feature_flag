package org.aibles.feature_flag.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.aibles.feature_flag.util.SecretCipher;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Wiring for outbound webhooks (issue #36). {@code @EnableAsync} already comes from {@link
 * NotificationConfig}, whose async pipeline the webhook dispatcher reuses.
 */
@Configuration
@EnableConfigurationProperties(WebhookProperties.class)
public class WebhookConfig {

  /**
   * A dedicated client with explicit timeouts. Without them a subscriber that accepts a connection
   * and never responds would pin an async thread indefinitely, and enough such endpoints would
   * exhaust the executor and stall every other notification.
   *
   * <p>Timeouts are set on a plain {@link SimpleClientHttpRequestFactory} rather than through
   * Boot's client-settings helpers, which have moved package/name across recent Boot versions.
   */
  @Bean
  public RestClient webhookRestClient(WebhookProperties properties) {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(properties.connectTimeout());
    requestFactory.setReadTimeout(properties.readTimeout());
    return RestClient.builder().requestFactory(requestFactory).build();
  }

  /**
   * A self-contained mapper rather than the injected one.
   *
   * <p>Boot 4.1 does not autoconfigure an {@code ObjectMapper} in a non-web ({@code
   * webEnvironment=NONE}) context, so injecting one breaks every {@code @SpringBootTest} repository
   * context — the same trap issue #31 hit with {@code AuditService}. Building our own also pins the
   * webhook body format: dates as ISO-8601 strings, independent of any global config change.
   */
  @Bean
  public ObjectMapper webhookObjectMapper() {
    return new ObjectMapper()
        .findAndRegisterModules()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  }

  @Bean
  public SecretCipher secretCipher(WebhookProperties properties) {
    return new SecretCipher(properties.encryptionKey());
  }
}
