package org.aibles.feature_flag.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class EnvironmentResponse {
    private UUID id;
    private String name;
    private String description;
    private UUID projectId;
    private String apiKey;
    private LocalDateTime createdAt;
}
