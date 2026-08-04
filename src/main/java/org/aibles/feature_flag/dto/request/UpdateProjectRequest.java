package org.aibles.feature_flag.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateProjectRequest {
  @Size(max = 255)
  private String name;

  private String description;
}
