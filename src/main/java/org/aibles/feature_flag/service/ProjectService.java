package org.aibles.feature_flag.service;

import java.util.List;
import java.util.UUID;
import org.aibles.feature_flag.dto.request.CreateProjectRequest;
import org.aibles.feature_flag.dto.request.UpdateProjectRequest;
import org.aibles.feature_flag.dto.response.ProjectResponse;

public interface ProjectService {
  ProjectResponse create(CreateProjectRequest request);

  List<ProjectResponse> listByOrganisation(UUID organisationId);

  ProjectResponse get(UUID id);

  ProjectResponse update(UUID id, UpdateProjectRequest request);

  void delete(UUID id);
}
