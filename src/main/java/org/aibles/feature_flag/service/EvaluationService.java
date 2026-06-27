package org.aibles.feature_flag.service;

import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.dto.response.FlagEvaluationResponse;

import java.util.List;

public interface EvaluationService {
    List<FlagEvaluationResponse> getAllFlags(Environment environment);
    FlagEvaluationResponse getFlag(Environment environment, String flagKey);
}
