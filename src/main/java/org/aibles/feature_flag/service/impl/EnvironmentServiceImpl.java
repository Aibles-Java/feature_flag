package org.aibles.feature_flag.service.impl;

import lombok.RequiredArgsConstructor;
import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.domain.entity.Project;
import org.aibles.feature_flag.domain.enums.Action;
import org.aibles.feature_flag.domain.enums.EnvType;
import org.aibles.feature_flag.dto.request.CreateEnvironmentRequest;
import org.aibles.feature_flag.dto.request.UpdateEnvironmentRequest;
import org.aibles.feature_flag.dto.response.EnvironmentResponse;
import org.aibles.feature_flag.exception.DuplicateResourceException;
import org.aibles.feature_flag.exception.ResourceNotFoundException;
import org.aibles.feature_flag.repository.EnvironmentRepository;
import org.aibles.feature_flag.repository.ProjectRepository;
import org.aibles.feature_flag.service.EnvironmentService;
import org.aibles.feature_flag.util.ApiKeyGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EnvironmentServiceImpl implements EnvironmentService {

    private final EnvironmentRepository environmentRepository;
    private final ProjectRepository projectRepository;
    private final PermissionService permissionService;

    @Override
    @Transactional
    public EnvironmentResponse create(CreateEnvironmentRequest request) {
        permissionService.check(Action.ENV_CREATE, PermissionService.ResourceRef.project(request.getProjectId()));
        if (environmentRepository.existsByProjectIdAndName(request.getProjectId(), request.getName())) {
            throw new DuplicateResourceException("Environment name already exists in this project");
        }
        Project project = projectRepository.findById(request.getProjectId())
                .orElseThrow(() -> new ResourceNotFoundException("Project", request.getProjectId()));

        Environment env = Environment.builder()
                .project(project)
                .name(request.getName())
                .description(request.getDescription())
                .type(request.getType() != null ? request.getType() : EnvType.DEVELOPMENT)
                .changeWindowStartHour(request.getChangeWindowStartHour())
                .changeWindowEndHour(request.getChangeWindowEndHour())
                .apiKey(ApiKeyGenerator.generate())
                .build();
        return toResponse(environmentRepository.save(env));
    }

    @Override
    public List<EnvironmentResponse> listByProject(UUID projectId) {
        permissionService.check(Action.ENV_READ, PermissionService.ResourceRef.project(projectId));
        return environmentRepository.findAllByProjectId(projectId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    public EnvironmentResponse get(UUID id) {
        Environment env = findById(id);
        permissionService.check(Action.ENV_READ, PermissionService.ResourceRef.project(env.getProject().getId()));
        return toResponse(env);
    }

    @Override
    @Transactional
    public EnvironmentResponse update(UUID id, UpdateEnvironmentRequest request) {
        Environment env = findById(id);
        permissionService.check(Action.ENV_UPDATE, PermissionService.ResourceRef.project(env.getProject().getId()));

        // Weakening protection attributes must not be possible below OWNER.
        boolean changingType = request.getType() != null && request.getType() != env.getType();
        boolean changingWindow =
                (request.getChangeWindowStartHour() != null
                        && !Objects.equals(request.getChangeWindowStartHour(), env.getChangeWindowStartHour()))
                || (request.getChangeWindowEndHour() != null
                        && !Objects.equals(request.getChangeWindowEndHour(), env.getChangeWindowEndHour()));
        if (changingType || changingWindow) {
            permissionService.check(Action.ENV_MANAGE_PROTECTION,
                    PermissionService.ResourceRef.project(env.getProject().getId()));
        }

        if (request.getName() != null) env.setName(request.getName());
        if (request.getDescription() != null) env.setDescription(request.getDescription());
        if (request.getType() != null) env.setType(request.getType());
        if (request.getChangeWindowStartHour() != null) env.setChangeWindowStartHour(request.getChangeWindowStartHour());
        if (request.getChangeWindowEndHour() != null) env.setChangeWindowEndHour(request.getChangeWindowEndHour());
        return toResponse(environmentRepository.save(env));
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        Environment env = findById(id);
        permissionService.check(Action.ENV_DELETE, PermissionService.ResourceRef.project(env.getProject().getId()));
        environmentRepository.delete(env);
    }

    @Override
    @Transactional
    public EnvironmentResponse rotateApiKey(UUID id) {
        Environment env = findById(id);
        permissionService.check(Action.ENV_ROTATE_KEY, PermissionService.ResourceRef.project(env.getProject().getId()));
        env.setApiKey(ApiKeyGenerator.generate());
        return toResponse(environmentRepository.save(env));
    }

    private Environment findById(UUID id) {
        return environmentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Environment", id));
    }

    private EnvironmentResponse toResponse(Environment env) {
        return EnvironmentResponse.builder()
                .id(env.getId())
                .name(env.getName())
                .description(env.getDescription())
                .projectId(env.getProject().getId())
                .type(env.getType())
                .changeWindowStartHour(env.getChangeWindowStartHour())
                .changeWindowEndHour(env.getChangeWindowEndHour())
                .apiKey(env.getApiKey())
                .createdAt(env.getCreatedAt())
                .build();
    }
}
