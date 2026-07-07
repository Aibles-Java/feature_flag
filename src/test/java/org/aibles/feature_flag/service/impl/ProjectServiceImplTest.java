package org.aibles.feature_flag.service.impl;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProjectServiceImplTest {

    @Mock ProjectRepository projectRepository;
    @Mock OrganizationRepository organizationRepository;
    @Mock PermissionService permissionService;

    ProjectServiceImpl service;

    UUID orgId = UUID.randomUUID();
    UUID projectId = UUID.randomUUID();
    Organization org;
    Project project;

    @BeforeEach
    void setUp() {
        service = new ProjectServiceImpl(projectRepository, organizationRepository, permissionService);
        org = Organization.builder().id(orgId).name("Acme").slug("acme").build();
        project = Project.builder().id(projectId).organization(org).name("Backend").build();
        doNothing().when(permissionService).requireRole(any(), any(MemberRole[].class));
    }

    @Test
    void create_throwsDuplicate_whenProjectNameExistsInOrg() {
        when(projectRepository.existsByOrganizationIdAndName(orgId, "Backend")).thenReturn(true);

        CreateProjectRequest req = new CreateProjectRequest();
        req.setOrganisationId(orgId);
        req.setName("Backend");

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(DuplicateResourceException.class);
        verify(projectRepository, never()).save(any());
    }

    @Test
    void create_savesProject_whenNameIsUnique() {
        when(projectRepository.existsByOrganizationIdAndName(orgId, "New")).thenReturn(false);
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
        when(projectRepository.save(any())).thenReturn(project);

        CreateProjectRequest req = new CreateProjectRequest();
        req.setOrganisationId(orgId);
        req.setName("New");

        ProjectResponse response = service.create(req);

        assertThat(response).isNotNull();
        verify(projectRepository).save(any(Project.class));
    }

    @Test
    void create_throwsResourceNotFound_whenOrgDoesNotExist() {
        when(projectRepository.existsByOrganizationIdAndName(orgId, "X")).thenReturn(false);
        when(organizationRepository.findById(orgId)).thenReturn(Optional.empty());

        CreateProjectRequest req = new CreateProjectRequest();
        req.setOrganisationId(orgId);
        req.setName("X");

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void update_changesNameAndDescription() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(projectRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateProjectRequest req = new UpdateProjectRequest();
        req.setName("Renamed");
        req.setDescription("New desc");

        ProjectResponse response = service.update(projectId, req);

        assertThat(response.getName()).isEqualTo("Renamed");
        assertThat(response.getDescription()).isEqualTo("New desc");
    }

    @Test
    void listByOrganisation_delegatesToRepository() {
        when(projectRepository.findAllByOrganizationId(orgId)).thenReturn(List.of(project));

        List<ProjectResponse> result = service.listByOrganisation(orgId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("Backend");
    }

    @Test
    void get_throwsResourceNotFound_whenProjectDoesNotExist() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(projectId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void delete_removesProject() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));

        service.delete(projectId);

        verify(projectRepository).delete(project);
    }
}
