package org.aibles.feature_flag.dto.response;

import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class FlagStateResponse {
  private UUID flagId;
  private UUID environmentId;
  private boolean enabled;
  private String value;
  private int rolloutPercent;
}
