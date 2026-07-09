package org.aibles.feature_flag.service.impl;

import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.domain.entity.FeatureFlag;
import org.aibles.feature_flag.domain.entity.FlagEnvironmentState;
import org.aibles.feature_flag.domain.entity.Project;
import org.aibles.feature_flag.domain.enums.FlagValueType;
import org.aibles.feature_flag.domain.enums.MemberRole;
import org.aibles.feature_flag.dto.request.CreateFeatureFlagRequest;
import org.aibles.feature_flag.dto.request.UpdateFeatureFlagRequest;
import org.aibles.feature_flag.dto.request.UpdateFlagStateRequest;
import org.aibles.feature_flag.dto.response.FeatureFlagResponse;
import org.aibles.feature_flag.dto.response.FlagStateResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.aibles.feature_flag.exception.DuplicateResourceException;
import org.aibles.feature_flag.exception.ResourceNotFoundException;
import org.aibles.feature_flag.metrics.FeatureFlagMetrics;
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
class FeatureFlagServiceImplTest {

    @Mock FeatureFlagRepository featureFlagRepository;
    @Mock ProjectRepository projectRepository;
    @Mock EnvironmentRepository environmentRepository;
    @Mock FlagEnvironmentStateRepository flagStateRepository;
    @Mock PermissionService permissionService;

    FeatureFlagServiceImpl service;

    UUID projectId = UUID.randomUUID();
    Project project;

    @BeforeEach
    void setUp() {
        service = new FeatureFlagServiceImpl(
                featureFlagRepository, projectRepository, environmentRepository,
                flagStateRepository, permissionService,
                new FeatureFlagMetrics(new SimpleMeterRegistry()));
        project = Project.builder().id(projectId).name("proj").build();
        doNothing().when(permissionService).requireRoleForProject(any(), any(MemberRole[].class));
    }

    @Test
    void create_autoCreatesOneStateRowPerEnvironment() {
        UUID env1Id = UUID.randomUUID();
        UUID env2Id = UUID.randomUUID();
        Environment env1 = Environment.builder().id(env1Id).name("prod").project(project).apiKeyHash("k1").build();
        Environment env2 = Environment.builder().id(env2Id).name("staging").project(project).apiKeyHash("k2").build();

        when(featureFlagRepository.existsByProjectIdAndKey(projectId, "my-flag")).thenReturn(false);
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        FeatureFlag saved = FeatureFlag.builder()
                .id(UUID.randomUUID()).project(project).name("My Flag").key("my-flag")
                .valueType(FlagValueType.BOOLEAN).build();
        when(featureFlagRepository.save(any())).thenReturn(saved);
        when(environmentRepository.findAllByProjectId(projectId)).thenReturn(List.of(env1, env2));
        when(flagStateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateFeatureFlagRequest req = new CreateFeatureFlagRequest();
        req.setProjectId(projectId);
        req.setName("My Flag");
        req.setKey("my-flag");
        req.setValueType(FlagValueType.BOOLEAN);

        service.create(req);

        ArgumentCaptor<FlagEnvironmentState> captor = ArgumentCaptor.forClass(FlagEnvironmentState.class);
        verify(flagStateRepository, times(2)).save(captor.capture());
        List<UUID> savedEnvIds = captor.getAllValues().stream()
                .map(s -> s.getEnvironment().getId()).toList();
        assertThat(savedEnvIds).containsExactlyInAnyOrder(env1Id, env2Id);
    }

    @Test
    void create_throwsDuplicate_whenKeyAlreadyExistsInProject() {
        when(featureFlagRepository.existsByProjectIdAndKey(projectId, "dup")).thenReturn(true);

        CreateFeatureFlagRequest req = new CreateFeatureFlagRequest();
        req.setProjectId(projectId);
        req.setKey("dup");
        req.setName("Dup");
        req.setValueType(FlagValueType.BOOLEAN);

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(DuplicateResourceException.class);
        verify(featureFlagRepository, never()).save(any());
    }

    @Test
    void create_doesNotCreateAnyStates_whenProjectHasNoEnvironments() {
        when(featureFlagRepository.existsByProjectIdAndKey(projectId, "new-flag")).thenReturn(false);
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        FeatureFlag saved = FeatureFlag.builder()
                .id(UUID.randomUUID()).project(project).name("N").key("new-flag")
                .valueType(FlagValueType.STRING).build();
        when(featureFlagRepository.save(any())).thenReturn(saved);
        when(environmentRepository.findAllByProjectId(projectId)).thenReturn(List.of());

        CreateFeatureFlagRequest req = new CreateFeatureFlagRequest();
        req.setProjectId(projectId);
        req.setName("N");
        req.setKey("new-flag");
        req.setValueType(FlagValueType.STRING);

        service.create(req);

        verify(flagStateRepository, never()).save(any());
    }

    @Test
    void update_changesNameAndDescription_butKeyRemainsImmutable() {
        UUID flagId = UUID.randomUUID();
        FeatureFlag flag = FeatureFlag.builder()
                .id(flagId).project(project).name("Old Name").key("original-key")
                .valueType(FlagValueType.BOOLEAN).build();

        when(featureFlagRepository.findById(flagId)).thenReturn(Optional.of(flag));
        when(featureFlagRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateFeatureFlagRequest req = new UpdateFeatureFlagRequest();
        req.setName("New Name");
        req.setDescription("Updated desc");

        FeatureFlagResponse response = service.update(flagId, req);

        assertThat(response.getKey()).isEqualTo("original-key");
        assertThat(response.getName()).isEqualTo("New Name");
        assertThat(response.getDescription()).isEqualTo("Updated desc");
    }

    @Test
    void archive_setsArchivedTrue() {
        UUID flagId = UUID.randomUUID();
        FeatureFlag flag = FeatureFlag.builder()
                .id(flagId).project(project).name("F").key("f").valueType(FlagValueType.BOOLEAN)
                .archived(false).build();

        when(featureFlagRepository.findById(flagId)).thenReturn(Optional.of(flag));
        when(featureFlagRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.archive(flagId);

        assertThat(flag.isArchived()).isTrue();
        verify(featureFlagRepository).save(flag);
    }

    @Test
    void unarchive_setsArchivedFalse() {
        UUID flagId = UUID.randomUUID();
        FeatureFlag flag = FeatureFlag.builder()
                .id(flagId).project(project).name("F").key("f").valueType(FlagValueType.BOOLEAN)
                .archived(true).build();

        when(featureFlagRepository.findById(flagId)).thenReturn(Optional.of(flag));
        when(featureFlagRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.unarchive(flagId);

        assertThat(flag.isArchived()).isFalse();
    }

    @Test
    void get_throwsResourceNotFound_whenFlagDoesNotExist() {
        UUID flagId = UUID.randomUUID();
        when(featureFlagRepository.findById(flagId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(flagId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void listByProject_delegatesToRepository() {
        FeatureFlag flag = FeatureFlag.builder()
                .id(UUID.randomUUID()).project(project).name("F").key("f")
                .valueType(FlagValueType.BOOLEAN).archived(false).build();
        when(featureFlagRepository.findAllByProjectIdAndArchivedFalse(projectId))
                .thenReturn(List.of(flag));

        List<FeatureFlagResponse> result = service.listByProject(projectId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getKey()).isEqualTo("f");
    }

    @Test
    void getState_returnsState_whenExists() {
        UUID flagId = UUID.randomUUID();
        UUID envId = UUID.randomUUID();
        FeatureFlag flag = FeatureFlag.builder().id(flagId).project(project).name("F").key("f")
                .valueType(FlagValueType.BOOLEAN).archived(false).build();
        Environment env = Environment.builder().id(envId).name("prod").apiKeyHash("k").build();
        FlagEnvironmentState state = FlagEnvironmentState.builder()
                .id(UUID.randomUUID()).featureFlag(flag).environment(env).enabled(true).value("true").build();
        when(featureFlagRepository.findById(flagId)).thenReturn(Optional.of(flag));
        when(flagStateRepository.findByFeatureFlagIdAndEnvironmentId(flagId, envId)).thenReturn(Optional.of(state));

        FlagStateResponse result = service.getState(flagId, envId);

        assertThat(result.isEnabled()).isTrue();
    }

    @Test
    void getState_throwsNotFound_whenFlagDoesNotExist() {
        UUID flagId = UUID.randomUUID();
        UUID envId = UUID.randomUUID();
        when(featureFlagRepository.findById(flagId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getState(flagId, envId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getState_throwsNotFound_whenStateDoesNotExist() {
        UUID flagId = UUID.randomUUID();
        UUID envId = UUID.randomUUID();
        FeatureFlag flag = FeatureFlag.builder().id(flagId).project(project).name("F").key("f")
                .valueType(FlagValueType.BOOLEAN).archived(false).build();
        when(featureFlagRepository.findById(flagId)).thenReturn(Optional.of(flag));
        when(flagStateRepository.findByFeatureFlagIdAndEnvironmentId(flagId, envId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getState(flagId, envId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateState_updatesEnabledAndValue() {
        UUID flagId = UUID.randomUUID();
        UUID envId = UUID.randomUUID();
        FeatureFlag flag = FeatureFlag.builder().id(flagId).project(project).name("F").key("f")
                .valueType(FlagValueType.BOOLEAN).archived(false).build();
        Environment env = Environment.builder().id(envId).name("prod").apiKeyHash("k").build();
        FlagEnvironmentState state = FlagEnvironmentState.builder()
                .id(UUID.randomUUID()).featureFlag(flag).environment(env).enabled(false).value("false").rolloutPercent(50).build();
        when(featureFlagRepository.findById(flagId)).thenReturn(Optional.of(flag));
        when(flagStateRepository.findByFeatureFlagIdAndEnvironmentId(flagId, envId)).thenReturn(Optional.of(state));
        when(flagStateRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        UpdateFlagStateRequest req = new UpdateFlagStateRequest();
        req.setEnabled(true);
        req.setValue("true");
        FlagStateResponse result = service.updateState(flagId, envId, req);

        assertThat(result.isEnabled()).isTrue();
        assertThat(result.getValue()).isEqualTo("true");
        assertThat(result.getRolloutPercent()).isEqualTo(50); // unchanged since request.rolloutPercent is null
    }

    @Test
    void updateState_updatesRolloutPercent_whenProvided() {
        UUID flagId = UUID.randomUUID();
        UUID envId = UUID.randomUUID();
        FeatureFlag flag = FeatureFlag.builder().id(flagId).project(project).name("F").key("f")
                .valueType(FlagValueType.BOOLEAN).archived(false).build();
        Environment env = Environment.builder().id(envId).name("prod").apiKeyHash("k").build();
        FlagEnvironmentState state = FlagEnvironmentState.builder()
                .id(UUID.randomUUID()).featureFlag(flag).environment(env).enabled(false).value("false").rolloutPercent(0).build();
        when(featureFlagRepository.findById(flagId)).thenReturn(Optional.of(flag));
        when(flagStateRepository.findByFeatureFlagIdAndEnvironmentId(flagId, envId)).thenReturn(Optional.of(state));
        when(flagStateRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        UpdateFlagStateRequest req = new UpdateFlagStateRequest();
        req.setEnabled(true);
        req.setValue("true");
        req.setRolloutPercent(75);
        FlagStateResponse result = service.updateState(flagId, envId, req);

        assertThat(result.getRolloutPercent()).isEqualTo(75);
    }

    @Test
    void updateState_throwsNotFound_whenFlagDoesNotExist() {
        UUID flagId = UUID.randomUUID();
        UUID envId = UUID.randomUUID();
        when(featureFlagRepository.findById(flagId)).thenReturn(Optional.empty());

        UpdateFlagStateRequest req = new UpdateFlagStateRequest();
        req.setEnabled(true);
        assertThatThrownBy(() -> service.updateState(flagId, envId, req))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateState_throwsNotFound_whenStateDoesNotExist() {
        UUID flagId = UUID.randomUUID();
        UUID envId = UUID.randomUUID();
        FeatureFlag flag = FeatureFlag.builder().id(flagId).project(project).name("F").key("f")
                .valueType(FlagValueType.BOOLEAN).archived(false).build();
        when(featureFlagRepository.findById(flagId)).thenReturn(Optional.of(flag));
        when(flagStateRepository.findByFeatureFlagIdAndEnvironmentId(flagId, envId)).thenReturn(Optional.empty());

        UpdateFlagStateRequest req = new UpdateFlagStateRequest();
        req.setEnabled(true);
        assertThatThrownBy(() -> service.updateState(flagId, envId, req))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
