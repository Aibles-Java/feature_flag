package org.aibles.feature_flag.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateFeatureFlagRequest {
    @Size(max = 255)
    private String name;
    private String description;
    // key is intentionally excluded — it is immutable
}
