package org.aibles.feature_flag.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ChangeWindowProperties.class)
public class AppConfig {

  /**
   * The application clock, pinned to the explicitly configured change-window zone ({@code
   * app.change-window.zone}) rather than the JVM default (D-09), so the production change window
   * does not shift with the host's TZ.
   */
  @Bean
  public Clock clock(ChangeWindowProperties properties) {
    return Clock.system(properties.zoneId());
  }
}
