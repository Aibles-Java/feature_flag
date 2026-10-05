package org.aibles.feature_flag.testsupport;

import java.time.Clock;
import org.aibles.feature_flag.config.AppConfig;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Pins BOTH production clocks to {@link SyntheticFixture#CLOCK}. The production context has exactly
 * one {@code @Primary} Clock ({@code clock}) plus the qualified {@code changeWindowClock} used by
 * PermissionService, so a test must override those two beans by name (requires {@code
 * spring.main.allow-bean-definition-overriding=true}) instead of adding a third {@code @Primary}
 * Clock, which fails with NoUniqueBeanDefinitionException.
 */
@TestConfiguration
public class FixedClocksTestConfig {

  @Bean
  @Primary
  public Clock clock() {
    return SyntheticFixture.CLOCK;
  }

  @Bean(AppConfig.CHANGE_WINDOW_CLOCK)
  public Clock changeWindowClock() {
    return SyntheticFixture.CLOCK;
  }
}
