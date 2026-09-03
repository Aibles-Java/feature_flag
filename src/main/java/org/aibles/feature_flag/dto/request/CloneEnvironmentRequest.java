package org.aibles.feature_flag.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Body of {@code POST /api/v1/environments/{id}/clone}. The clone always lands in the source
 * environment's project, so no {@code projectId} is accepted.
 */
@Data
public class CloneEnvironmentRequest {
  @NotBlank
  @Size(max = 100)
  private String name;

  private String description;
}
