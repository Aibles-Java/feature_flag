package org.aibles.feature_flag.service;

import org.aibles.feature_flag.dto.request.CreateEnvironmentRequest;
import org.aibles.feature_flag.dto.request.UpdateEnvironmentRequest;
import org.aibles.feature_flag.dto.response.EnvironmentResponse;

import java.util.List;
import java.util.UUID;

public interface EnvironmentService {
    EnvironmentResponse create(CreateEnvironmentRequest request);
    List<EnvironmentResponse> listByProject(UUID projectId);
    EnvironmentResponse get(UUID id);
    EnvironmentResponse update(UUID id, UpdateEnvironmentRequest request);
    void delete(UUID id);
    EnvironmentResponse rotateApiKey(UUID id);
}
