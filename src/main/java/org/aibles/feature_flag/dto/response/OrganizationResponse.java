package org.aibles.feature_flag.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OrganizationResponse {
  private UUID id;
  private String name;
  private String slug;
  private LocalDateTime createdAt;
}
