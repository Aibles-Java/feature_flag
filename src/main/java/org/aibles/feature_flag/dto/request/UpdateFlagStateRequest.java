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

  @Schema(
      description =
          "The state's version as last read (FlagStateResponse.version). If it no longer matches"
              + " the stored version the request is rejected with 409 and nothing is written."
              + " Optional during the D-05(2) transition (a warning is logged); required (400"
              + " when missing) once app.flag-state.require-version is enabled.",
      nullable = true)
  private Long version;
}
