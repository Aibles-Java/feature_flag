package org.aibles.feature_flag.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateEnvironmentRequest {
  @Size(max = 100)
  private String name;

  private String description;
}
