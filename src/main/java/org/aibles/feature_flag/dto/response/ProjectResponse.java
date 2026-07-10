package org.aibles.feature_flag.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ProjectResponse {
  private UUID id;
  private String name;
  private String description;
  private UUID organisationId;
  private LocalDateTime createdAt;
}
