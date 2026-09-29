package org.aibles.feature_flag.service;

import java.util.UUID;
import org.aibles.feature_flag.domain.enums.HygieneStatus;
import org.aibles.feature_flag.dto.response.FlagHygieneResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface FlagHygieneService {

  /**
   * The hygiene report for a project, one row per active (flag, environment) pair.
   *
   * @param status which rows to include — see {@link HygieneStatus}
   */
  Page<FlagHygieneResponse> report(UUID projectId, HygieneStatus status, Pageable pageable);
}
