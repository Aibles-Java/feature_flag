package org.aibles.feature_flag.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import org.aibles.feature_flag.service.FlagStateSnapshot;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

@Configuration
public class EvaluationCacheConfig {

  @Bean
  public Cache<UUID, List<FlagStateSnapshot>> evaluationCache(
      EvaluationCacheProperties properties, MeterRegistry meterRegistry) {
    Cache<UUID, List<FlagStateSnapshot>> cache =
        Caffeine.newBuilder()
            .maximumSize(properties.getMaxSize())
            .expireAfterWrite(properties.getTtl())
            .recordStats()
            .build();
    meterRegistry.gauge("evaluation.cache.hit_rate", cache, c -> c.stats().hitRate());
    meterRegistry.gauge("evaluation.cache.size", cache, c -> (double) c.estimatedSize());
    meterRegistry.gauge("evaluation.cache.miss_count", cache, c -> (double) c.stats().missCount());
    return cache;
  }

  @Bean
  public FilterRegistrationBean<ShallowEtagHeaderFilter> sdkEtagFilter() {
    FilterRegistrationBean<ShallowEtagHeaderFilter> bean =
        new FilterRegistrationBean<>(new ShallowEtagHeaderFilter());
    bean.addUrlPatterns("/api/v1/sdk/flags", "/api/v1/sdk/flags/*");
    return bean;
  }
}
