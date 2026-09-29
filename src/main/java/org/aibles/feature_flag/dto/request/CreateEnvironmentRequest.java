package org.aibles.feature_flag.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.Data;
import org.aibles.feature_flag.domain.enums.EnvType;

@Data
public class CreateEnvironmentRequest {
  @NotNull private UUID projectId;

  @NotBlank
  @Size(max = 100)
  private String name;

  private String description;

  private EnvType type;

  @Min(0)
  @Max(23)
  private Integer changeWindowStartHour;

  @Min(0)
  @Max(23)
  private Integer changeWindowEndHour;

  /**
   * IANA zone the window hours are read in, e.g. {@code Asia/Ho_Chi_Minh}. Null keeps the server's
   * zone, which is how every window behaved before this field existed.
   */
  private String changeWindowTimezone;

  @AssertTrue(message = "changeWindowTimezone must be a valid IANA zone id, e.g. Asia/Ho_Chi_Minh")
  public boolean isChangeWindowTimezoneValid() {
    if (changeWindowTimezone == null || changeWindowTimezone.isBlank()) {
      return true;
    }
    // Rejecting here is the whole reason the PDP can fall back quietly instead of throwing.
    return java.time.ZoneId.getAvailableZoneIds().contains(changeWindowTimezone);
  }

  @AssertTrue(message = "changeWindowStartHour and changeWindowEndHour must be provided together")
  public boolean isChangeWindowComplete() {
    return (changeWindowStartHour == null) == (changeWindowEndHour == null);
  }
}
