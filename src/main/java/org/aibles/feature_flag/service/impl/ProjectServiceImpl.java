package org.aibles.feature_flag.service.impl;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.aibles.feature_flag.domain.entity.Organization;
import org.aibles.feature_flag.domain.entity.Project;
import org.aibles.feature_flag.domain.enums.AuditAction;
import org.aibles.feature_flag.domain.enums.AuditEntityType;
import org.aibles.feature_flag.domain.enums.MemberRole;
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
    permissionService.requireRole(request.getOrganisationId(), MemberRole.OWNER, MemberRole.ADMIN);
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
    permissionService.requireRole(
        organisationId, MemberRole.OWNER, MemberRole.ADMIN, MemberRole.VIEWER);
    return projectRepository
        .findAllByOrganizationId(organisationId, pageable)
        .map(this::toResponse);
  }

  @Override
  public ProjectResponse get(UUID id) {
    Project project = findById(id);
    permissionService.requireRole(
        project.getOrganization().getId(), MemberRole.OWNER, MemberRole.ADMIN, MemberRole.VIEWER);
    return toResponse(project);
  }

  @Override
  @Transactional
  public ProjectResponse update(UUID id, UpdateProjectRequest request) {
    Project project = findById(id);
    UUID orgId = project.getOrganization().getId();
    permissionService.requireRole(orgId, MemberRole.OWNER, MemberRole.ADMIN);
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
    permissionService.requireRole(orgId, MemberRole.OWNER);
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
