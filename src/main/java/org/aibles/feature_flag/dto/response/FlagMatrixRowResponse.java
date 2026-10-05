package org.aibles.feature_flag.dto.response;

import java.util.List;
import lombok.Builder;
import lombok.Data;

/** One row of the flag x environment matrix (S-2.5, solution design 6.5): a flag and its states. */
@Data
@Builder
public class FlagMatrixRowResponse {
  private FeatureFlagResponse flag;
  private List<FlagStateResponse> states;
}
