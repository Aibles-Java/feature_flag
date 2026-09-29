package org.aibles.feature_flag.service;

import java.util.UUID;
import org.aibles.feature_flag.dto.request.CreateProjectRequest;
import org.aibles.feature_flag.dto.request.UpdateProjectRequest;
import org.aibles.feature_flag.dto.response.ProjectResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ProjectService {
  ProjectResponse create(CreateProjectRequest request);

  Page<ProjectResponse> listByOrganisation(UUID organisationId, Pageable pageable);

  ProjectResponse get(UUID id);

  ProjectResponse update(UUID id, UpdateProjectRequest request);

  void delete(UUID id);
}
