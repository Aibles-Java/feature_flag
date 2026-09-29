package org.aibles.feature_flag.service;

import java.util.UUID;
import org.aibles.feature_flag.dto.request.CreateProjectGrantRequest;
import org.aibles.feature_flag.dto.response.ProjectGrantResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ProjectGrantService {

  Page<ProjectGrantResponse> listGrants(UUID projectId, Pageable pageable);

  ProjectGrantResponse upsertGrant(UUID projectId, CreateProjectGrantRequest request);

  void revokeGrant(UUID projectId, UUID userId);
}
