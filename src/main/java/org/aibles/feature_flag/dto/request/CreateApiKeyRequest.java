package org.aibles.feature_flag.dto.request;

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

  /** Optional planned retirement. Absent means the key never expires. */
  @Future private LocalDateTime expiresAt;
}
