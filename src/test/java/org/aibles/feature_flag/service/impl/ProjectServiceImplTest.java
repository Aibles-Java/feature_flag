package org.aibles.feature_flag.service.impl;

import org.aibles.feature_flag.domain.entity.Organization;
import org.aibles.feature_flag.domain.entity.Project;
import org.aibles.feature_flag.domain.enums.MemberRole;
import org.aibles.feature_flag.dto.request.CreateProjectRequest;
import org.aibles.feature_flag.dto.request.UpdateProjectRequest;
import org.aibles.feature_flag.dto.response.ProjectResponse;
import org.aibles.feature_flag.exception.DuplicateResourceException;
import org.aibles.feature_flag.exception.ResourceNotFoundException;
import org.aibles.feature_flag.exception.UnauthorizedException;
import org.aibles.feature_flag.repository.OrganizationRepository;
import org.aibles.feature_flag.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectServiceImplTest {

    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private OrganizationRepository organizationRepository;
    @Mock
    private PermissionService permissionService;

    private ProjectServiceImpl service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private Organization org;

    @BeforeEach
    void setUp() {
        service = new ProjectServiceImpl(projectRepository, organizationRepository, permissionService);
        org = Organization.builder().id(orgId).name("Acme").slug("acme").build();
    }

    private CreateProjectRequest createRequest() {
        CreateProjectRequest request = new CreateProjectRequest();
        request.setOrganisationId(orgId);
        request.setName("Billing");
        request.setDescription("Billing service flags");
        return request;
    }

    private Project existingProject() {
        return Project.builder().id(projectId).organization(org).name("Billing").description("desc").build();
    }

    @Test
    void createPersistsProjectUnderOrganisation() {
        when(projectRepository.existsByOrganizationIdAndName(orgId, "Billing")).thenReturn(false);
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
        when(projectRepository.save(any(Project.class))).thenAnswer(inv -> inv.getArgument(0));

        ProjectResponse response = service.create(createRequest());

        assertThat(response.getName()).isEqualTo("Billing");
        assertThat(response.getOrganisationId()).isEqualTo(orgId);
    }

    @Test
    void createRejectsDuplicateName() {
        when(projectRepository.existsByOrganizationIdAndName(orgId, "Billing")).thenReturn(true);

        assertThatThrownBy(() -> service.create(createRequest()))
                .isInstanceOf(DuplicateResourceException.class);

        verify(projectRepository, never()).save(any());
    }

    @Test
    void createThrowsWhenOrganisationMissing() {
        when(projectRepository.existsByOrganizationIdAndName(orgId, "Billing")).thenReturn(false);
        when(organizationRepository.findById(orgId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(createRequest()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createIsBlockedForInsufficientRole() {
        doThrow(new UnauthorizedException("nope"))
                .when(permissionService).requireRole(orgId, MemberRole.OWNER, MemberRole.ADMIN);

        assertThatThrownBy(() -> service.create(createRequest()))
                .isInstanceOf(UnauthorizedException.class);

        verify(projectRepository, never()).save(any());
    }

    @Test
    void getThrowsWhenProjectMissing() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(projectId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getChecksRoleAgainstOwningOrganisation() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(existingProject()));

        service.get(projectId);

        verify(permissionService).requireRole(orgId, MemberRole.OWNER, MemberRole.ADMIN, MemberRole.VIEWER);
    }

    @Test
    void updateAppliesPartialChanges() {
        Project project = existingProject();
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(projectRepository.save(any(Project.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateProjectRequest request = new UpdateProjectRequest();
        request.setName("Billing v2");

        ProjectResponse response = service.update(projectId, request);

        assertThat(response.getName()).isEqualTo("Billing v2");
        assertThat(response.getDescription()).isEqualTo("desc");
    }

    @Test
    void deleteRemovesProject() {
        Project project = existingProject();
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));

        service.delete(projectId);

        verify(permissionService).requireRole(orgId, MemberRole.OWNER);
        verify(projectRepository).delete(project);
    }
}
