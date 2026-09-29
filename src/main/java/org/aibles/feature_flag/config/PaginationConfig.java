package org.aibles.feature_flag.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.config.PageableHandlerMethodArgumentResolverCustomizer;

/**
 * Global pagination limits for admin list endpoints (issue #33).
 *
 * <p>Enforces a hard maximum page size so a caller can never request an unbounded page (the whole
 * point of paginating — a single request must not be able to load an org's entire flag/audit set).
 * The customizer applies to every {@code Pageable} controller argument at once, so individual
 * controllers only declare the default sort via {@code @PageableDefault}. When no {@code size} is
 * supplied the resolver falls back to {@link #DEFAULT_PAGE_SIZE}; anything above {@link
 * #MAX_PAGE_SIZE} is clamped down to it.
 */
@Configuration
public class PaginationConfig {

  public static final int DEFAULT_PAGE_SIZE = 20;
  public static final int MAX_PAGE_SIZE = 100;

  @Bean
  public PageableHandlerMethodArgumentResolverCustomizer pageableCustomizer() {
    return resolver -> {
      resolver.setMaxPageSize(MAX_PAGE_SIZE);
      resolver.setFallbackPageable(PageRequest.of(0, DEFAULT_PAGE_SIZE));
    };
  }
}
