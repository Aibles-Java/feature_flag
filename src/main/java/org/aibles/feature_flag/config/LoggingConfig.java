package org.aibles.feature_flag.config;

import org.aibles.feature_flag.logging.RequestCorrelationFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Wires {@link RequestCorrelationFilter} into the servlet container ahead of Spring Security.
 *
 * <p>The filter is registered explicitly (rather than as a {@code @Component}) so we can pin its
 * order to {@link Ordered#HIGHEST_PRECEDENCE}. Spring Security's {@code FilterChainProxy} sits at
 * order {@code -100}, so this places correlation before <em>all</em> security chains — the SDK,
 * admin, and management chains alike — and applies it to every URL ({@code /*}).
 */
@Configuration
public class LoggingConfig {

  @Bean
  public FilterRegistrationBean<RequestCorrelationFilter> requestCorrelationFilter() {
    FilterRegistrationBean<RequestCorrelationFilter> registration =
        new FilterRegistrationBean<>(new RequestCorrelationFilter());
    registration.addUrlPatterns("/*");
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return registration;
  }
}
