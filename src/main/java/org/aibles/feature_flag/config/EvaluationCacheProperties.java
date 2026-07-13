package org.aibles.feature_flag.config;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.evaluation-cache")
public class EvaluationCacheProperties {
  private int maxSize = 1000;
  private Duration ttl = Duration.ofMinutes(5);
}
