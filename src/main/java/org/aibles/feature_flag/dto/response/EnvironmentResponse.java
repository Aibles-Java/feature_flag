package org.aibles.feature_flag.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class EnvironmentResponse {
  private UUID id;
  private String name;
  private String description;
  private UUID projectId;
  private LocalDateTime createdAt;
}
