package org.aibles.feature_flag.dto.request;

import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class UpdateFeatureFlagRequest {
  @Size(max = 255)
  private String name;

  private String description;

  /** Optional planned removal date; null leaves the current value unchanged (issue #37). */
  private LocalDateTime expiresAt;

  // key is intentionally excluded — it is immutable
}
