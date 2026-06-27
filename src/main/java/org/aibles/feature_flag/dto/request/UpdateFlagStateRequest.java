package org.aibles.feature_flag.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpdateFlagStateRequest {
    @NotNull
    private Boolean enabled;
    private String value;
}
