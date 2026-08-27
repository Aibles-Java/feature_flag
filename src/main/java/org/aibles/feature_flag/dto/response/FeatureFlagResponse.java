package org.aibles.feature_flag.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;
import org.aibles.feature_flag.domain.enums.FlagValueType;

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

  /** Optional planned removal date (issue #37). Reported, never auto-enforced. */
  private LocalDateTime expiresAt;

  private LocalDateTime createdAt;
}
