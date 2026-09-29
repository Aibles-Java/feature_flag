package org.aibles.feature_flag.service.impl;

import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.aibles.feature_flag.domain.entity.Organization;
import org.aibles.feature_flag.domain.entity.Project;
import org.aibles.feature_flag.domain.enums.Action;
import org.aibles.feature_flag.domain.enums.AuditAction;
import org.aibles.feature_flag.domain.enums.AuditEntityType;
import org.aibles.feature_flag.dto.request.CreateProjectRequest;
import org.aibles.feature_flag.dto.request.UpdateProjectRequest;
import org.aibles.feature_flag.dto.response.ProjectResponse;
import org.aibles.feature_flag.exception.DuplicateResourceException;
import org.aibles.feature_flag.exception.ResourceNotFoundException;
import org.aibles.feature_flag.repository.OrganizationRepository;
import org.aibles.feature_flag.repository.ProjectRepository;
import org.aibles.feature_flag.service.ProjectService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProjectServiceImpl implements ProjectService {

  private final ProjectRepository projectRepository;
  private final OrganizationRepository organizationRepository;
  private final PermissionService permissionService;
  private final AuditService auditService;

  @Override
  @Transactional
  public ProjectResponse create(CreateProjectRequest request) {
    permissionService.check(
        Action.PROJECT_CREATE, PermissionService.ResourceRef.org(request.getOrganisationId()));
    if (projectRepository.existsByOrganizationIdAndName(
        request.getOrganisationId(), request.getName())) {
      throw new DuplicateResourceException("Project name already exists in this organisation");
    }
    Organization org =
        organizationRepository
            .findById(request.getOrganisationId())
            .orElseThrow(
                () -> new ResourceNotFoundException("Organisation", request.getOrganisationId()));

    Project project =
        Project.builder()
            .organization(org)
            .name(request.getName())
            .description(request.getDescription())
            .build();
    ProjectResponse response = toResponse(projectRepository.save(project));
    auditService.record(
        AuditEntityType.PROJECT,
        response.getId(),
        AuditAction.CREATE,
        request.getOrganisationId(),
        null,
        response);
    return response;
  }

  @Override
  public Page<ProjectResponse> listByOrganisation(UUID organisationId, Pageable pageable) {
    // Membership is the floor: you have to be in the organisation to ask at all.
    permissionService.check(Action.ORG_READ, PermissionService.ResourceRef.org(organisationId));

    // A role carrying org-wide PROJECT_READ (VIEWER and up) sees everything, exactly as before —
    // and pays no extra query for the privilege.
    if (permissionService.hasOrgAction(Action.PROJECT_READ, organisationId)) {
      return projectRepository
          .findAllByOrganizationId(organisationId, pageable)
          .map(this::toResponse);
    }

    // Otherwise the list narrows to the projects a grant actually reaches. Hiding rows here would
    // be decoration on its own; it works because get(id) already asks at project scope, so a
    // hidden project is unreadable by id too.
    Set<UUID> reachable = permissionService.grantedProjectIds(Action.PROJECT_READ);
    if (reachable.isEmpty()) {
      return Page.empty(pageable);
    }
    return projectRepository
        .findAllByOrganizationIdAndIdIn(organisationId, reachable, pageable)
        .map(this::toResponse);
  }

  @Override
  public ProjectResponse get(UUID id) {
    Project project = findById(id);
    permissionService.check(Action.PROJECT_READ, PermissionService.ResourceRef.project(id));
    return toResponse(project);
  }

  @Override
  @Transactional
  public ProjectResponse update(UUID id, UpdateProjectRequest request) {
    Project project = findById(id);
    UUID orgId = project.getOrganization().getId();
    permissionService.check(Action.PROJECT_UPDATE, PermissionService.ResourceRef.project(id));
    ProjectResponse before = toResponse(project);
    if (request.getName() != null) project.setName(request.getName());
    if (request.getDescription() != null) project.setDescription(request.getDescription());
    ProjectResponse after = toResponse(projectRepository.save(project));
    auditService.record(AuditEntityType.PROJECT, id, AuditAction.UPDATE, orgId, before, after);
    return after;
  }

  @Override
  @Transactional
  public void delete(UUID id) {
    Project project = findById(id);
    UUID orgId = project.getOrganization().getId();
    permissionService.check(Action.PROJECT_DELETE, PermissionService.ResourceRef.project(id));
    ProjectResponse before = toResponse(project);
    projectRepository.delete(project);
    auditService.record(AuditEntityType.PROJECT, id, AuditAction.DELETE, orgId, before, null);
  }

  private Project findById(UUID id) {
    return projectRepository
        .findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Project", id));
  }

  private ProjectResponse toResponse(Project p) {
    return ProjectResponse.builder()
        .id(p.getId())
        .name(p.getName())
        .description(p.getDescription())
        .organisationId(p.getOrganization().getId())
        .createdAt(p.getCreatedAt())
        .build();
  }
}
