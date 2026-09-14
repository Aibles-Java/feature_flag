package org.aibles.feature_flag.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class CreateApiKeyRequestTest {

  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

  private CreateApiKeyRequest request(LocalDateTime expiresAt, boolean neverExpires) {
    CreateApiKeyRequest request = new CreateApiKeyRequest();
    request.setName("ios-app");
    request.setExpiresAt(expiresAt);
    request.setNeverExpires(neverExpires);
    return request;
  }

  @Test
  void rejectsAnExplicitExpiryTogetherWithNeverExpires() {
    assertThat(validator.validate(request(LocalDateTime.now().plusDays(1), true)))
        .extracting(ConstraintViolation::getMessage)
        .containsExactly("expiresAt and neverExpires cannot both be set");
  }

  @Test
  void acceptsNeverExpiresOnItsOwn() {
    assertThat(validator.validate(request(null, true))).isEmpty();
  }

  @Test
  void acceptsNeitherFieldSoTheDefaultLifetimeApplies() {
    assertThat(validator.validate(request(null, false))).isEmpty();
  }
}
