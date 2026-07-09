package org.aibles.feature_flag.service.impl;

import lombok.RequiredArgsConstructor;
import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.domain.entity.FeatureFlag;
import org.aibles.feature_flag.domain.entity.FlagEnvironmentState;
import org.aibles.feature_flag.domain.entity.Project;
import org.aibles.feature_flag.domain.enums.MemberRole;
import org.aibles.feature_flag.dto.request.CreateFeatureFlagRequest;
import org.aibles.feature_flag.dto.request.UpdateFeatureFlagRequest;
import org.aibles.feature_flag.dto.request.UpdateFlagStateRequest;
import org.aibles.feature_flag.dto.response.FeatureFlagResponse;
import org.aibles.feature_flag.dto.response.FlagStateResponse;
import org.aibles.feature_flag.exception.DuplicateResourceException;
import org.aibles.feature_flag.exception.ResourceNotFoundException;
import org.aibles.feature_flag.metrics.FeatureFlagMetrics;
import org.aibles.feature_flag.repository.EnvironmentRepository;
import org.aibles.feature_flag.repository.FeatureFlagRepository;
import org.aibles.feature_flag.repository.FlagEnvironmentStateRepository;
import org.aibles.feature_flag.repository.ProjectRepository;
import org.aibles.feature_flag.service.FeatureFlagService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FeatureFlagServiceImpl implements FeatureFlagService {

    private final FeatureFlagRepository featureFlagRepository;
    private final ProjectRepository projectRepository;
    private final EnvironmentRepository environmentRepository;
    private final FlagEnvironmentStateRepository flagStateRepository;
    private final PermissionService permissionService;
    private final FeatureFlagMetrics metrics;

    @Override
    @Transactional
    public FeatureFlagResponse create(CreateFeatureFlagRequest request) {
        permissionService.requireRoleForProject(request.getProjectId(), MemberRole.OWNER, MemberRole.ADMIN);

        if (featureFlagRepository.existsByProjectIdAndKey(request.getProjectId(), request.getKey())) {
            throw new DuplicateResourceException("Flag key already exists in this project: " + request.getKey());
        }

        Project project = projectRepository.findById(request.getProjectId())
                .orElseThrow(() -> new ResourceNotFoundException("Project", request.getProjectId()));

        FeatureFlag flag = FeatureFlag.builder()
                .project(project)
                .name(request.getName())
                .key(request.getKey())
                .description(request.getDescription())
                .valueType(request.getValueType())
                .build();
        flag = featureFlagRepository.save(flag);

        // Auto-create FlagEnvironmentState for all existing environments in the project
        List<Environment> environments = environmentRepository.findAllByProjectId(request.getProjectId());
        for (Environment env : environments) {
            FlagEnvironmentState state = FlagEnvironmentState.builder()
                    .featureFlag(flag)
                    .environment(env)
                    .enabled(false)
                    .build();
            flagStateRepository.save(state);
        }

        metrics.recordFlagChange(FeatureFlagMetrics.FlagChange.CREATED);
        return toResponse(flag);
    }

    @Override
    public List<FeatureFlagResponse> listByProject(UUID projectId) {
        permissionService.requireRoleForProject(projectId, MemberRole.OWNER, MemberRole.ADMIN, MemberRole.VIEWER);
        return featureFlagRepository.findAllByProjectIdAndArchivedFalse(projectId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    public FeatureFlagResponse get(UUID id) {
        FeatureFlag flag = findById(id);
        permissionService.requireRoleForProject(flag.getProject().getId(), MemberRole.OWNER, MemberRole.ADMIN, MemberRole.VIEWER);
        return toResponse(flag);
    }

    @Override
    @Transactional
    public FeatureFlagResponse update(UUID id, UpdateFeatureFlagRequest request) {
        FeatureFlag flag = findById(id);
        permissionService.requireRoleForProject(flag.getProject().getId(), MemberRole.OWNER, MemberRole.ADMIN);
        // key is intentionally not updated — it is immutable
        if (request.getName() != null) flag.setName(request.getName());
        if (request.getDescription() != null) flag.setDescription(request.getDescription());
        FeatureFlagResponse response = toResponse(featureFlagRepository.save(flag));
        metrics.recordFlagChange(FeatureFlagMetrics.FlagChange.UPDATED);
        return response;
    }

    @Override
    @Transactional
    public void archive(UUID id) {
        FeatureFlag flag = findById(id);
        permissionService.requireRoleForProject(flag.getProject().getId(), MemberRole.OWNER, MemberRole.ADMIN);
        flag.setArchived(true);
        featureFlagRepository.save(flag);
        metrics.recordFlagChange(FeatureFlagMetrics.FlagChange.ARCHIVED);
    }

    @Override
    @Transactional
    public void unarchive(UUID id) {
        FeatureFlag flag = findById(id);
        permissionService.requireRoleForProject(flag.getProject().getId(), MemberRole.OWNER, MemberRole.ADMIN);
        flag.setArchived(false);
        featureFlagRepository.save(flag);
        metrics.recordFlagChange(FeatureFlagMetrics.FlagChange.UNARCHIVED);
    }

    @Override
    public List<FeatureFlagResponse> listArchivedByProject(UUID projectId) {
        permissionService.requireRoleForProject(projectId, MemberRole.OWNER, MemberRole.ADMIN, MemberRole.VIEWER);
        return featureFlagRepository.findAllByProjectIdAndArchivedTrue(projectId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    public FlagStateResponse getState(UUID flagId, UUID environmentId) {
        FeatureFlag flag = findById(flagId);
        permissionService.requireRoleForProject(flag.getProject().getId(), MemberRole.OWNER, MemberRole.ADMIN, MemberRole.VIEWER);
        FlagEnvironmentState state = flagStateRepository
                .findByFeatureFlagIdAndEnvironmentId(flagId, environmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Flag state not found for this environment"));
        return toStateResponse(state);
    }

    @Override
    @Transactional
    public FlagStateResponse updateState(UUID flagId, UUID environmentId, UpdateFlagStateRequest request) {
        FeatureFlag flag = findById(flagId);
        permissionService.requireRoleForProject(flag.getProject().getId(), MemberRole.OWNER, MemberRole.ADMIN);

        FlagEnvironmentState state = flagStateRepository
                .findByFeatureFlagIdAndEnvironmentId(flagId, environmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Flag state not found for this environment"));

        state.setEnabled(request.getEnabled());
        state.setValue(request.getValue());
        if (request.getRolloutPercent() != null) state.setRolloutPercent(request.getRolloutPercent());
        FlagStateResponse response = toStateResponse(flagStateRepository.save(state));
        metrics.recordFlagChange(FeatureFlagMetrics.FlagChange.STATE_UPDATED);
        return response;
    }

    private FeatureFlag findById(UUID id) {
        return featureFlagRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("FeatureFlag", id));
    }

    private FeatureFlagResponse toResponse(FeatureFlag flag) {
        return FeatureFlagResponse.builder()
                .id(flag.getId())
                .name(flag.getName())
                .key(flag.getKey())
                .description(flag.getDescription())
                .valueType(flag.getValueType())
                .archived(flag.isArchived())
                .projectId(flag.getProject().getId())
                .createdAt(flag.getCreatedAt())
                .build();
    }

    private FlagStateResponse toStateResponse(FlagEnvironmentState state) {
        return FlagStateResponse.builder()
                .flagId(state.getFeatureFlag().getId())
                .environmentId(state.getEnvironment().getId())
                .enabled(state.isEnabled())
                .value(state.getValue())
                .rolloutPercent(state.getRolloutPercent())
                .build();
    }
}
