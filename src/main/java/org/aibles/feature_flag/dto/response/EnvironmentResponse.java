package org.aibles.feature_flag.dto.response;

import lombok.Builder;
import lombok.Data;
import org.aibles.feature_flag.domain.enums.EnvType;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class EnvironmentResponse {
    private UUID id;
    private String name;
    private String description;
    private UUID projectId;
    private EnvType type;
    private Integer changeWindowStartHour;
    private Integer changeWindowEndHour;
    private String apiKey;
    private LocalDateTime createdAt;
}
