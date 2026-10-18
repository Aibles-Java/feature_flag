package org.aibles.feature_flag.service;

import java.util.List;
import java.util.UUID;
import org.aibles.feature_flag.dto.request.CreateFeatureFlagRequest;
import org.aibles.feature_flag.dto.request.UpdateFeatureFlagRequest;
import org.aibles.feature_flag.dto.request.UpdateFlagStateRequest;
import org.aibles.feature_flag.dto.response.FeatureFlagResponse;
import org.aibles.feature_flag.dto.response.FlagStateResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface FeatureFlagService {
  FeatureFlagResponse create(CreateFeatureFlagRequest request);

  Page<FeatureFlagResponse> listByProject(UUID projectId, Pageable pageable);

  FeatureFlagResponse get(UUID id);

  FeatureFlagResponse update(UUID id, UpdateFeatureFlagRequest request);

  void archive(UUID id);

  void unarchive(UUID id);

  Page<FeatureFlagResponse> listArchivedByProject(UUID projectId, Pageable pageable);

  /** All states of one flag (project taken from the flag; needs FLAG_READ on it). */
  List<FlagStateResponse> listStates(UUID flagId);

  FlagStateResponse getState(UUID flagId, UUID environmentId);

  FlagStateResponse updateState(UUID flagId, UUID environmentId, UpdateFlagStateRequest request);
}
