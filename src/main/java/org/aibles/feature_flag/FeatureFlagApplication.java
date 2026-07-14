package org.aibles.feature_flag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FeatureFlagApplication {

  public static void main(String[] args) {
    SpringApplication.run(FeatureFlagApplication.class, args);
  }
}
