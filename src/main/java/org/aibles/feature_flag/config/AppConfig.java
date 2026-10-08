package org.aibles.feature_flag.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
@EnableConfigurationProperties(ChangeWindowProperties.class)
public class AppConfig {

  public static final String CHANGE_WINDOW_CLOCK = "changeWindowClock";

  /**
   * General application clock (API-key expiry, schedulers, ...). Deliberately unchanged: those
   * paths compare against zone-less LocalDateTime values written in the JVM zone.
   */
  @Bean
  @Primary
  public Clock clock() {
    return Clock.systemDefaultZone();
  }

  /**
   * Clock for the production change window only, pinned to the explicitly configured zone ({@code
   * app.change-window.zone}) rather than the JVM default (D-09).
   */
  @Bean(CHANGE_WINDOW_CLOCK)
  public Clock changeWindowClock(ChangeWindowProperties properties) {
    return Clock.system(properties.zoneId());
  }
}
