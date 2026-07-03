package org.aibles.feature_flag.service.impl;

import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.domain.entity.Organization;
import org.aibles.feature_flag.domain.entity.Project;
import org.aibles.feature_flag.domain.enums.MemberRole;
import org.aibles.feature_flag.dto.request.CreateEnvironmentRequest;
import org.aibles.feature_flag.dto.request.UpdateEnvironmentRequest;
import org.aibles.feature_flag.dto.response.EnvironmentResponse;
import org.aibles.feature_flag.exception.DuplicateResourceException;
import org.aibles.feature_flag.exception.ResourceNotFoundException;
import org.aibles.feature_flag.exception.UnauthorizedException;
import org.aibles.feature_flag.repository.EnvironmentRepository;
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
class EnvironmentServiceImplTest {

    @Mock
    private EnvironmentRepository environmentRepository;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private PermissionService permissionService;

    private EnvironmentServiceImpl service;

    private final UUID projectId = UUID.randomUUID();
    private final UUID envId = UUID.randomUUID();
    private Project project;

    @BeforeEach
    void setUp() {
        service = new EnvironmentServiceImpl(environmentRepository, projectRepository, permissionService);
        project = Project.builder()
                .id(projectId)
                .organization(Organization.builder().id(UUID.randomUUID()).build())
                .build();
    }

    private CreateEnvironmentRequest createRequest() {
        CreateEnvironmentRequest request = new CreateEnvironmentRequest();
        request.setProjectId(projectId);
        request.setName("production");
        request.setDescription("prod env");
        return request;
    }

    private Environment existingEnv() {
        return Environment.builder()
                .id(envId)
                .project(project)
                .name("production")
                .apiKey("original-api-key")
                .build();
    }

    @Test
    void createGeneratesApiKeyAndPersists() {
        when(environmentRepository.existsByProjectIdAndName(projectId, "production")).thenReturn(false);
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(environmentRepository.save(any(Environment.class))).thenAnswer(inv -> inv.getArgument(0));

        EnvironmentResponse response = service.create(createRequest());

        assertThat(response.getName()).isEqualTo("production");
        assertThat(response.getProjectId()).isEqualTo(projectId);

        assertThat(response.getApiKey()).isNotBlank().hasSize(64);
    }

    @Test
    void createRejectsDuplicateName() {
        when(environmentRepository.existsByProjectIdAndName(projectId, "production")).thenReturn(true);

        assertThatThrownBy(() -> service.create(createRequest()))
                .isInstanceOf(DuplicateResourceException.class);

        verify(environmentRepository, never()).save(any());
    }

    @Test
    void createThrowsWhenProjectMissing() {
        when(environmentRepository.existsByProjectIdAndName(projectId, "production")).thenReturn(false);
        when(projectRepository.findById(projectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(createRequest()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createIsBlockedForInsufficientRole() {
        doThrow(new UnauthorizedException("nope"))
                .when(permissionService).requireRoleForProject(projectId, MemberRole.OWNER, MemberRole.ADMIN);

        assertThatThrownBy(() -> service.create(createRequest()))
                .isInstanceOf(UnauthorizedException.class);

        verify(environmentRepository, never()).save(any());
    }

    @Test
    void getThrowsWhenEnvironmentMissing() {
        when(environmentRepository.findById(envId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(envId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateAppliesPartialChanges() {
        Environment env = existingEnv();
        when(environmentRepository.findById(envId)).thenReturn(Optional.of(env));
        when(environmentRepository.save(any(Environment.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateEnvironmentRequest request = new UpdateEnvironmentRequest();
        request.setDescription("new description");

        EnvironmentResponse response = service.update(envId, request);

        assertThat(response.getName()).isEqualTo("production");
        assertThat(response.getDescription()).isEqualTo("new description");
    }

    @Test
    void deleteRemovesById() {
        service.delete(envId);

        verify(permissionService).requireRoleForEnvironment(envId, MemberRole.OWNER);
        verify(environmentRepository).deleteById(envId);
    }

    @Test
    void rotateApiKeyReplacesKeyWithNewValue() {
        Environment env = existingEnv();
        when(environmentRepository.findById(envId)).thenReturn(Optional.of(env));
        when(environmentRepository.save(any(Environment.class))).thenAnswer(inv -> inv.getArgument(0));

        EnvironmentResponse response = service.rotateApiKey(envId);

        assertThat(response.getApiKey())
                .isNotEqualTo("original-api-key")
                .hasSize(64);
    }
}
