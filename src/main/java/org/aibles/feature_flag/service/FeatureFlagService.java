package org.aibles.feature_flag.service;

import org.aibles.feature_flag.dto.request.CreateFeatureFlagRequest;
import org.aibles.feature_flag.dto.request.UpdateFeatureFlagRequest;
import org.aibles.feature_flag.dto.request.UpdateFlagStateRequest;
import org.aibles.feature_flag.dto.response.FeatureFlagResponse;
import org.aibles.feature_flag.dto.response.FlagStateResponse;

import java.util.List;
import java.util.UUID;

public interface FeatureFlagService {
    FeatureFlagResponse create(CreateFeatureFlagRequest request);
    List<FeatureFlagResponse> listByProject(UUID projectId);
    FeatureFlagResponse get(UUID id);
    FeatureFlagResponse update(UUID id, UpdateFeatureFlagRequest request);
    void archive(UUID id);
    void unarchive(UUID id);
    List<FeatureFlagResponse> listArchivedByProject(UUID projectId);
    FlagStateResponse getState(UUID flagId, UUID environmentId);
    FlagStateResponse updateState(UUID flagId, UUID environmentId, UpdateFlagStateRequest request);
}
