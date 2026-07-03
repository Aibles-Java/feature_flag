package org.aibles.feature_flag.service.impl;

import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.domain.entity.FeatureFlag;
import org.aibles.feature_flag.domain.entity.FlagEnvironmentState;
import org.aibles.feature_flag.domain.entity.Organization;
import org.aibles.feature_flag.domain.entity.Project;
import org.aibles.feature_flag.domain.enums.FlagValueType;
import org.aibles.feature_flag.domain.enums.MemberRole;
import org.aibles.feature_flag.dto.request.CreateFeatureFlagRequest;
import org.aibles.feature_flag.dto.request.UpdateFeatureFlagRequest;
import org.aibles.feature_flag.dto.request.UpdateFlagStateRequest;
import org.aibles.feature_flag.dto.response.FeatureFlagResponse;
import org.aibles.feature_flag.dto.response.FlagStateResponse;
import org.aibles.feature_flag.exception.DuplicateResourceException;
import org.aibles.feature_flag.exception.ResourceNotFoundException;
import org.aibles.feature_flag.repository.EnvironmentRepository;
import org.aibles.feature_flag.repository.FeatureFlagRepository;
import org.aibles.feature_flag.repository.FlagEnvironmentStateRepository;
import org.aibles.feature_flag.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeatureFlagServiceImplTest {

    @Mock
    private FeatureFlagRepository featureFlagRepository;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private EnvironmentRepository environmentRepository;
    @Mock
    private FlagEnvironmentStateRepository flagStateRepository;
    @Mock
    private PermissionService permissionService;

    private FeatureFlagServiceImpl service;

    private final UUID projectId = UUID.randomUUID();
    private final UUID flagId = UUID.randomUUID();
    private Project project;

    @BeforeEach
    void setUp() {
        service = new FeatureFlagServiceImpl(
                featureFlagRepository, projectRepository, environmentRepository, flagStateRepository, permissionService);
        project = Project.builder()
                .id(projectId)
                .organization(Organization.builder().id(UUID.randomUUID()).build())
                .build();
    }

    private CreateFeatureFlagRequest createRequest() {
        CreateFeatureFlagRequest request = new CreateFeatureFlagRequest();
        request.setProjectId(projectId);
        request.setName("Dark Mode");
        request.setKey("dark-mode");
        request.setDescription("Toggle dark mode");
        request.setValueType(FlagValueType.BOOLEAN);
        return request;
    }

    private FeatureFlag existingFlag() {
        return FeatureFlag.builder()
                .id(flagId)
                .project(project)
                .name("Dark Mode")
                .key("dark-mode")
                .valueType(FlagValueType.BOOLEAN)
                .archived(false)
                .build();
    }

    @Test
    void createAutoCreatesDisabledStateForEveryEnvironment() {
        CreateFeatureFlagRequest request = createRequest();
        when(featureFlagRepository.existsByProjectIdAndKey(projectId, "dark-mode")).thenReturn(false);
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(featureFlagRepository.save(any(FeatureFlag.class))).thenAnswer(inv -> inv.getArgument(0));
        Environment dev = Environment.builder().id(UUID.randomUUID()).project(project).build();
        Environment prod = Environment.builder().id(UUID.randomUUID()).project(project).build();
        when(environmentRepository.findAllByProjectId(projectId)).thenReturn(List.of(dev, prod));

        FeatureFlagResponse response = service.create(request);

        ArgumentCaptor<FlagEnvironmentState> states = ArgumentCaptor.forClass(FlagEnvironmentState.class);
        verify(flagStateRepository, times(2)).save(states.capture());

        assertThat(states.getAllValues())
                .allSatisfy(s -> assertThat(s.isEnabled()).isFalse())
                .extracting(s -> s.getEnvironment().getId())
                .containsExactlyInAnyOrder(dev.getId(), prod.getId());
        assertThat(response.getKey()).isEqualTo("dark-mode");
    }

    @Test
    void createSavesNoStatesWhenProjectHasNoEnvironments() {
        CreateFeatureFlagRequest request = createRequest();
        when(featureFlagRepository.existsByProjectIdAndKey(projectId, "dark-mode")).thenReturn(false);
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(featureFlagRepository.save(any(FeatureFlag.class))).thenAnswer(inv -> inv.getArgument(0));
        when(environmentRepository.findAllByProjectId(projectId)).thenReturn(List.of());

        service.create(request);

        verify(flagStateRepository, never()).save(any());
    }

    @Test
    void createRequiresOwnerOrAdminRole() {
        CreateFeatureFlagRequest request = createRequest();
        org.mockito.Mockito.doThrow(new org.aibles.feature_flag.exception.UnauthorizedException("nope"))
                .when(permissionService).requireRoleForProject(projectId, MemberRole.OWNER, MemberRole.ADMIN);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(org.aibles.feature_flag.exception.UnauthorizedException.class);

        verify(featureFlagRepository, never()).save(any());
    }

    @Test
    void createRejectsDuplicateKey() {
        CreateFeatureFlagRequest request = createRequest();
        when(featureFlagRepository.existsByProjectIdAndKey(projectId, "dark-mode")).thenReturn(true);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("dark-mode");

        verify(featureFlagRepository, never()).save(any());
    }

    @Test
    void createThrowsWhenProjectMissing() {
        CreateFeatureFlagRequest request = createRequest();
        when(featureFlagRepository.existsByProjectIdAndKey(projectId, "dark-mode")).thenReturn(false);
        when(projectRepository.findById(projectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateNeverChangesKey() {
        FeatureFlag flag = existingFlag();
        when(featureFlagRepository.findById(flagId)).thenReturn(Optional.of(flag));
        when(featureFlagRepository.save(any(FeatureFlag.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateFeatureFlagRequest request = new UpdateFeatureFlagRequest();
        request.setName("Dark Mode v2");
        request.setDescription("Updated");

        FeatureFlagResponse response = service.update(flagId, request);

        assertThat(response.getName()).isEqualTo("Dark Mode v2");
        assertThat(response.getDescription()).isEqualTo("Updated");
        assertThat(response.getKey()).isEqualTo("dark-mode");
        assertThat(flag.getKey()).isEqualTo("dark-mode");
    }

    @Test
    void updateWithNullFieldsLeavesExistingValues() {
        FeatureFlag flag = existingFlag();
        flag.setDescription("original");
        when(featureFlagRepository.findById(flagId)).thenReturn(Optional.of(flag));
        when(featureFlagRepository.save(any(FeatureFlag.class))).thenAnswer(inv -> inv.getArgument(0));

        FeatureFlagResponse response = service.update(flagId, new UpdateFeatureFlagRequest());

        assertThat(response.getName()).isEqualTo("Dark Mode");
        assertThat(response.getDescription()).isEqualTo("original");
        assertThat(response.getKey()).isEqualTo("dark-mode");
    }

    @Test
    void updateThrowsWhenFlagMissing() {
        when(featureFlagRepository.findById(flagId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(flagId, new UpdateFeatureFlagRequest()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void archiveSetsArchivedTrue() {
        FeatureFlag flag = existingFlag();
        when(featureFlagRepository.findById(flagId)).thenReturn(Optional.of(flag));

        service.archive(flagId);

        assertThat(flag.isArchived()).isTrue();
        verify(featureFlagRepository).save(flag);
    }

    @Test
    void unarchiveSetsArchivedFalse() {
        FeatureFlag flag = existingFlag();
        flag.setArchived(true);
        when(featureFlagRepository.findById(flagId)).thenReturn(Optional.of(flag));

        service.unarchive(flagId);

        assertThat(flag.isArchived()).isFalse();
        verify(featureFlagRepository).save(flag);
    }

    @Test
    void listByProjectReturnsOnlyActiveFlags() {
        when(featureFlagRepository.findAllByProjectIdAndArchivedFalse(projectId))
                .thenReturn(List.of(existingFlag()));

        List<FeatureFlagResponse> result = service.listByProject(projectId);

        assertThat(result).hasSize(1);
        verify(featureFlagRepository).findAllByProjectIdAndArchivedFalse(projectId);
    }

    @Test
    void listArchivedByProjectQueriesArchivedFlags() {
        FeatureFlag archived = existingFlag();
        archived.setArchived(true);
        when(featureFlagRepository.findAllByProjectIdAndArchivedTrue(projectId))
                .thenReturn(List.of(archived));

        List<FeatureFlagResponse> result = service.listArchivedByProject(projectId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).isArchived()).isTrue();
    }

    @Test
    void getStateReturnsStateForEnvironment() {
        UUID envId = UUID.randomUUID();
        FeatureFlag flag = existingFlag();
        Environment env = Environment.builder().id(envId).project(project).build();
        FlagEnvironmentState state = FlagEnvironmentState.builder()
                .featureFlag(flag).environment(env).enabled(true).value("v").rolloutPercent(50).build();
        when(featureFlagRepository.findById(flagId)).thenReturn(Optional.of(flag));
        when(flagStateRepository.findByFeatureFlagIdAndEnvironmentId(flagId, envId))
                .thenReturn(Optional.of(state));

        FlagStateResponse response = service.getState(flagId, envId);

        assertThat(response.isEnabled()).isTrue();
        assertThat(response.getValue()).isEqualTo("v");
        assertThat(response.getRolloutPercent()).isEqualTo(50);
    }

    @Test
    void getStateThrowsWhenStateMissing() {
        UUID envId = UUID.randomUUID();
        FeatureFlag flag = existingFlag();
        when(featureFlagRepository.findById(flagId)).thenReturn(Optional.of(flag));
        when(flagStateRepository.findByFeatureFlagIdAndEnvironmentId(flagId, envId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getState(flagId, envId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateStateAppliesEnabledValueAndRollout() {
        UUID envId = UUID.randomUUID();
        FeatureFlag flag = existingFlag();
        Environment env = Environment.builder().id(envId).project(project).build();
        FlagEnvironmentState state = FlagEnvironmentState.builder()
                .featureFlag(flag).environment(env).enabled(false).rolloutPercent(100).build();
        when(featureFlagRepository.findById(flagId)).thenReturn(Optional.of(flag));
        when(flagStateRepository.findByFeatureFlagIdAndEnvironmentId(flagId, envId))
                .thenReturn(Optional.of(state));
        when(flagStateRepository.save(any(FlagEnvironmentState.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateFlagStateRequest request = new UpdateFlagStateRequest();
        request.setEnabled(true);
        request.setValue("on");
        request.setRolloutPercent(25);

        FlagStateResponse response = service.updateState(flagId, envId, request);

        assertThat(response.isEnabled()).isTrue();
        assertThat(response.getValue()).isEqualTo("on");
        assertThat(response.getRolloutPercent()).isEqualTo(25);
    }

    @Test
    void updateStateLeavesRolloutUntouchedWhenNull() {
        UUID envId = UUID.randomUUID();
        FeatureFlag flag = existingFlag();
        Environment env = Environment.builder().id(envId).project(project).build();
        FlagEnvironmentState state = FlagEnvironmentState.builder()
                .featureFlag(flag).environment(env).enabled(false).rolloutPercent(100).build();
        when(featureFlagRepository.findById(flagId)).thenReturn(Optional.of(flag));
        when(flagStateRepository.findByFeatureFlagIdAndEnvironmentId(flagId, envId))
                .thenReturn(Optional.of(state));
        when(flagStateRepository.save(any(FlagEnvironmentState.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateFlagStateRequest request = new UpdateFlagStateRequest();
        request.setEnabled(true);
        request.setValue("on");

        FlagStateResponse response = service.updateState(flagId, envId, request);

        assertThat(response.getRolloutPercent()).isEqualTo(100);
    }
}
