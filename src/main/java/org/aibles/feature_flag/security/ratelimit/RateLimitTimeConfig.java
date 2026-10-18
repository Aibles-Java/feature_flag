package org.aibles.feature_flag.security.ratelimit;

import io.github.bucket4j.TimeMeter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Time source for the rate limiter; a separate bean so tests can substitute a manual meter. */
@Configuration
public class RateLimitTimeConfig {

  @Bean
  public TimeMeter rateLimitTimeMeter() {
    return TimeMeter.SYSTEM_MILLISECONDS;
  }
}
