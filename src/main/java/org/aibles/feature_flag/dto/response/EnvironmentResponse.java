package org.aibles.feature_flag.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;
import org.aibles.feature_flag.domain.enums.EnvType;

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
  private LocalDateTime createdAt;
}
