package org.aibles.feature_flag.dto.response;

import lombok.Builder;
import lombok.Data;
import org.aibles.feature_flag.domain.enums.FlagValueType;

@Data
@Builder
public class FlagEvaluationResponse {
  private String flagKey;
  private boolean enabled;
  private String value;
  private FlagValueType valueType;
  private int rolloutPercent;
}
