package org.aibles.feature_flag.service;

import java.util.List;
import java.util.UUID;
import org.aibles.feature_flag.dto.request.CreateProjectGrantRequest;
import org.aibles.feature_flag.dto.response.ProjectGrantResponse;

public interface ProjectGrantService {

  List<ProjectGrantResponse> listGrants(UUID projectId);

  ProjectGrantResponse upsertGrant(UUID projectId, CreateProjectGrantRequest request);

  void revokeGrant(UUID projectId, UUID userId);
}
