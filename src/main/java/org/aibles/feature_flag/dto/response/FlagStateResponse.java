package org.aibles.feature_flag.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
public class FlagStateResponse {
    private UUID flagId;
    private UUID environmentId;
    private boolean enabled;
    private String value;
    private int rolloutPercent;
}
