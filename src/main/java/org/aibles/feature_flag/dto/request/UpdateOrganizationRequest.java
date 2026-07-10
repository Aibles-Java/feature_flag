package org.aibles.feature_flag.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateOrganizationRequest {
  @Size(max = 255)
  private String name;
}
