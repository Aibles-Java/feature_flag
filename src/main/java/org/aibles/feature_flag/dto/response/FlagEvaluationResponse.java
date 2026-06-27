package org.aibles.feature_flag.dto.response;

import lombok.Builder;
import lombok.Data;
import org.aibles.feature_flag.domain.entity.FlagEnvironmentState;
import org.aibles.feature_flag.domain.enums.FlagValueType;

@Data
@Builder
public class FlagEvaluationResponse {
    private String flagKey;
    private boolean enabled;
    private String value;
    private FlagValueType valueType;

    public static FlagEvaluationResponse from(FlagEnvironmentState state) {
        return FlagEvaluationResponse.builder()
                .flagKey(state.getFeatureFlag().getKey())
                .enabled(state.isEnabled())
                .value(state.getValue())
                .valueType(state.getFeatureFlag().getValueType())
                .build();
    }
}
