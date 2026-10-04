package org.aibles.feature_flag.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpdateFlagStateRequest {
  @NotNull private Boolean enabled;

  @Schema(
      description =
          "New value. Absent or null keeps the stored value unchanged (ADR-05); use clearValue to"
              + " remove it.")
  private String value;

  @Schema(
      description =
          "true clears the stored value (sets it to null). Must not be combined with a non-null"
              + " value (400). Absent/false keeps the value.",
      defaultValue = "false")
  private Boolean clearValue;

  @jakarta.validation.constraints.Min(0)
  @jakarta.validation.constraints.Max(100)
  private Integer rolloutPercent;
}
