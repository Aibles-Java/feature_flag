package org.aibles.feature_flag.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** S-0.5 (D-10): the value length limit is configuration, not a constant. */
class FlagValuePropertiesTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(Cfg.class);

  @Test
  void defaultsTo8192() {
    runner.run(
        ctx -> assertThat(ctx.getBean(FlagValueProperties.class).maxLength()).isEqualTo(8192));
  }

  @Test
  void isOverridableFromProperties() {
    runner
        .withPropertyValues("app.flag-value.max-length=100")
        .run(ctx -> assertThat(ctx.getBean(FlagValueProperties.class).maxLength()).isEqualTo(100));
  }

  @Test
  void rejectsNonPositiveLimit() {
    runner
        .withPropertyValues("app.flag-value.max-length=0")
        .run(ctx -> assertThat(ctx).hasFailed());
  }

  @EnableConfigurationProperties(FlagValueProperties.class)
  static class Cfg {}
}
