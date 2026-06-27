package org.aibles.feature_flag.dto.response;

import lombok.Builder;
import lombok.Data;
import org.aibles.feature_flag.domain.enums.FlagValueType;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class FeatureFlagResponse {
    private UUID id;
    private String name;
    private String key;
    private String description;
    private FlagValueType valueType;
    private boolean archived;
    private UUID projectId;
    private LocalDateTime createdAt;
}
