package org.aibles.feature_flag.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class CreateApiKeyRequest {

  /** Operator-facing label. Not unique — keys are told apart by prefix and creation time. */
  @NotBlank
  @Size(max = 100)
  private String name;

  /**
   * Planned retirement. When absent the key gets the configured default lifetime ({@code
   * app.api-key.default-ttl}) unless {@link #neverExpires} is set.
   */
  @Future private LocalDateTime expiresAt;

  /** Explicitly create a key that never expires. Cannot be combined with {@code expiresAt}. */
  private boolean neverExpires;

  @AssertTrue(message = "expiresAt and neverExpires cannot both be set")
  public boolean isExpiryUnambiguous() {
    return !(neverExpires && expiresAt != null);
  }
}
