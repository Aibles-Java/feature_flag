package org.aibles.feature_flag.testsupport;

import java.time.Clock;
import java.util.Set;
import org.aibles.feature_flag.config.AppConfig;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Pins BOTH production clocks to {@link SyntheticFixture#CLOCK}: the {@code @Primary} {@code clock}
 * and the qualified {@code changeWindowClock} used by PermissionService.
 *
 * <p>The clocks are swapped by a {@link BeanPostProcessor} rather than by redefining the beans
 * under the same names. With bean-definition overriding the definition registered LAST wins, and a
 * test-level {@code @Import} is registered BEFORE the application's {@code AppConfig}, so the real
 * system clocks silently won and every change-window test passed only between 09:00 and 17:00 UTC.
 * Replacing the instances after creation does not depend on registration order.
 */
@TestConfiguration
public class FixedClocksTestConfig {

  private static final Set<String> CLOCK_BEANS = Set.of("clock", AppConfig.CHANGE_WINDOW_CLOCK);

  @Bean
  static BeanPostProcessor fixedClocksPostProcessor() {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessAfterInitialization(Object bean, String beanName) {
        return bean instanceof Clock && CLOCK_BEANS.contains(beanName)
            ? SyntheticFixture.CLOCK
            : bean;
      }
    };
  }
}
