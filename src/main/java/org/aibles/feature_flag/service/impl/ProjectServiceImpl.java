package org.aibles.feature_flag.service.impl;

import lombok.RequiredArgsConstructor;
import org.aibles.feature_flag.domain.entity.Organization;
import org.aibles.feature_flag.domain.entity.Project;
import org.aibles.feature_flag.domain.enums.MemberRole;
import org.aibles.feature_flag.dto.request.CreateProjectRequest;
import org.aibles.feature_flag.dto.request.UpdateProjectRequest;
import org.aibles.feature_flag.dto.response.ProjectResponse;
import org.aibles.feature_flag.exception.DuplicateResourceException;
import org.aibles.feature_flag.exception.ResourceNotFoundException;
import org.aibles.feature_flag.repository.OrganizationRepository;
import org.aibles.feature_flag.repository.ProjectRepository;
import org.aibles.feature_flag.service.ProjectService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProjectServiceImpl implements ProjectService {

    private final ProjectRepository projectRepository;
    private final OrganizationRepository organizationRepository;
    private final PermissionService permissionService;

    @Override
    @Transactional
    public ProjectResponse create(CreateProjectRequest request) {
        permissionService.requireRole(request.getOrganisationId(), MemberRole.OWNER, MemberRole.ADMIN);
        if (projectRepository.existsByOrganizationIdAndName(request.getOrganisationId(), request.getName())) {
            throw new DuplicateResourceException("Project name already exists in this organisation");
        }
        Organization org = organizationRepository.findById(request.getOrganisationId())
                .orElseThrow(() -> new ResourceNotFoundException("Organisation", request.getOrganisationId()));

        Project project = Project.builder()
                .organization(org)
                .name(request.getName())
                .description(request.getDescription())
                .build();
        return toResponse(projectRepository.save(project));
    }

    @Override
    public List<ProjectResponse> listByOrganisation(UUID organisationId) {
        permissionService.requireRole(organisationId, MemberRole.OWNER, MemberRole.ADMIN, MemberRole.VIEWER);
        return projectRepository.findAllByOrganizationId(organisationId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    public ProjectResponse get(UUID id) {
        Project project = findById(id);
        permissionService.requireRole(project.getOrganization().getId(), MemberRole.OWNER, MemberRole.ADMIN, MemberRole.VIEWER);
        return toResponse(project);
    }

    @Override
    @Transactional
    public ProjectResponse update(UUID id, UpdateProjectRequest request) {
        Project project = findById(id);
        permissionService.requireRole(project.getOrganization().getId(), MemberRole.OWNER, MemberRole.ADMIN);
        if (request.getName() != null) project.setName(request.getName());
        if (request.getDescription() != null) project.setDescription(request.getDescription());
        return toResponse(projectRepository.save(project));
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        Project project = findById(id);
        permissionService.requireRole(project.getOrganization().getId(), MemberRole.OWNER);
        projectRepository.delete(project);
    }

    private Project findById(UUID id) {
        return projectRepository.findById(id)
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
