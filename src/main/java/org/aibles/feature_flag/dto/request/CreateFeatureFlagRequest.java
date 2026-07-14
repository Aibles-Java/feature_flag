package org.aibles.feature_flag.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.Data;
import org.aibles.feature_flag.domain.enums.FlagValueType;

@Data
public class CreateFeatureFlagRequest {
  @NotNull private UUID projectId;

  @NotBlank
  @Size(max = 255)
  private String name;

  @NotBlank
  @Size(max = 255)
  @Pattern(
      regexp = "^[a-z0-9-_]+$",
      message = "Key must be lowercase alphanumeric with hyphens or underscores")
  private String key;

  private String description;

  @NotNull private FlagValueType valueType;
}
