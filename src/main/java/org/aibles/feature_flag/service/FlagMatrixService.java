package org.aibles.feature_flag.service;

import java.util.UUID;
import org.aibles.feature_flag.dto.response.FlagMatrixRowResponse;
import org.aibles.feature_flag.dto.response.PageResponse;

/** Read-only flag x environment matrix for one project (S-2.5, ADR-02). */
public interface FlagMatrixService {
  /** Matrix page, paginated by flag; {@code size} is clamped to the global maximum. */
  PageResponse<FlagMatrixRowResponse> getMatrix(UUID projectId, int page, int size);
}
