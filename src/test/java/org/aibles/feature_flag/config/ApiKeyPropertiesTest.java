package org.aibles.feature_flag.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ApiKeyPropertiesTest {

  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  void appliesTheDesignDefaultsWhenNothingIsConfigured() {
    ApiKeyProperties props = new ApiKeyProperties(null, null);

    assertThat(props.defaultTtl()).isEqualTo(Duration.ofDays(90));
    assertThat(props.expiryWarning().enabled()).isTrue();
    assertThat(props.expiryWarning().cron()).isEqualTo("0 0 9 * * *");
    assertThat(props.expiryWarning().thresholdsDays()).containsExactly(30, 7, 1);
    assertThat(validator.validate(props)).isEmpty();
  }

  @Test
  void rejectsAZeroDefaultTtl() {
    assertThat(validator.validate(new ApiKeyProperties(Duration.ZERO, null)))
        .extracting(ConstraintViolation::getMessage)
        .containsExactly("app.api-key.default-ttl must be a positive duration");
  }

  @Test
  void rejectsANonPositiveThreshold() {
    ApiKeyProperties props =
        new ApiKeyProperties(null, new ApiKeyProperties.ExpiryWarning(true, null, List.of(30, 0)));

    assertThat(validator.validate(props))
        .extracting(ConstraintViolation::getMessage)
        .containsExactly("app.api-key.expiry-warning.thresholds-days must all be positive");
  }
}
