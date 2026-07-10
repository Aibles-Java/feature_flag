package org.aibles.feature_flag.service;

import java.util.List;
import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.dto.response.FlagEvaluationResponse;

public interface EvaluationService {
  List<FlagEvaluationResponse> getAllFlags(Environment environment, String identifier);

  FlagEvaluationResponse getFlag(Environment environment, String flagKey, String identifier);
}
