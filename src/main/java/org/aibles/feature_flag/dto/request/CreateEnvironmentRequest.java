package org.aibles.feature_flag.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.aibles.feature_flag.domain.enums.EnvType;

import java.util.UUID;

@Data
public class CreateEnvironmentRequest {
    @NotNull
    private UUID projectId;

    @NotBlank @Size(max = 100)
    private String name;

    private String description;

    private EnvType type;

    @Min(0) @Max(23)
    private Integer changeWindowStartHour;
    @Min(0) @Max(23)
    private Integer changeWindowEndHour;

    @AssertTrue(message = "changeWindowStartHour and changeWindowEndHour must be provided together")
    public boolean isChangeWindowComplete() {
        return (changeWindowStartHour == null) == (changeWindowEndHour == null);
    }
}
