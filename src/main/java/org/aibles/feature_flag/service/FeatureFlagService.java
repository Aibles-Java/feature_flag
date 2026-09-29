package org.aibles.feature_flag.service;

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

  FlagStateResponse getState(UUID flagId, UUID environmentId);

  FlagStateResponse updateState(UUID flagId, UUID environmentId, UpdateFlagStateRequest request);
}
