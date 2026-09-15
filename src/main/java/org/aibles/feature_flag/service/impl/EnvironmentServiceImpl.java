package org.aibles.feature_flag.service.impl;

import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.domain.entity.FeatureFlag;
import org.aibles.feature_flag.domain.entity.FlagEnvironmentState;
import org.aibles.feature_flag.domain.entity.Project;
import org.aibles.feature_flag.domain.enums.Action;
import org.aibles.feature_flag.domain.enums.AuditAction;
import org.aibles.feature_flag.domain.enums.AuditEntityType;
import org.aibles.feature_flag.domain.enums.EnvType;
import org.aibles.feature_flag.dto.request.CreateEnvironmentRequest;
import org.aibles.feature_flag.dto.request.UpdateEnvironmentRequest;
import org.aibles.feature_flag.dto.response.EnvironmentResponse;
import org.aibles.feature_flag.dto.response.EnvironmentSecretResponse;
import org.aibles.feature_flag.exception.DuplicateResourceException;
import org.aibles.feature_flag.exception.ResourceNotFoundException;
import org.aibles.feature_flag.notification.event.ApiKeyRotatedEvent;
import org.aibles.feature_flag.repository.EnvironmentRepository;
import org.aibles.feature_flag.repository.FeatureFlagRepository;
import org.aibles.feature_flag.repository.FlagEnvironmentStateRepository;
import org.aibles.feature_flag.repository.ProjectRepository;
import org.aibles.feature_flag.service.EnvironmentService;
import org.aibles.feature_flag.util.ApiKeyGenerator;
import org.aibles.feature_flag.util.ApiKeyHasher;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EnvironmentServiceImpl implements EnvironmentService {

  private final EnvironmentRepository environmentRepository;
  private final ProjectRepository projectRepository;
  private final PermissionService permissionService;
  private final ApplicationEventPublisher eventPublisher;
  private final AuditService auditService;
  private final FeatureFlagRepository featureFlagRepository;
  private final FlagEnvironmentStateRepository flagStateRepository;

  @Override
  @Transactional
  public EnvironmentSecretResponse create(CreateEnvironmentRequest request) {
    permissionService.check(
        Action.ENV_CREATE, PermissionService.ResourceRef.project(request.getProjectId()));
    if (environmentRepository.existsByProjectIdAndName(request.getProjectId(), request.getName())) {
      throw new DuplicateResourceException("Environment name already exists in this project");
    }
    Project project =
        projectRepository
            .findById(request.getProjectId())
            .orElseThrow(() -> new ResourceNotFoundException("Project", request.getProjectId()));

    String plaintextKey = ApiKeyGenerator.generate();
    Environment env =
        Environment.builder()
            .project(project)
            .name(request.getName())
            .description(request.getDescription())
            .type(request.getType() != null ? request.getType() : EnvType.DEVELOPMENT)
            .changeWindowStartHour(request.getChangeWindowStartHour())
            .changeWindowEndHour(request.getChangeWindowEndHour())
            .apiKeyHash(ApiKeyHasher.hash(plaintextKey))
            .build();
    Environment saved = environmentRepository.save(env);
    backfillFlagStates(project, saved);
    // Audit the non-secret view only — never the plaintext key.
    auditService.record(
        AuditEntityType.ENVIRONMENT,
        saved.getId(),
        AuditAction.CREATE,
        project.getOrganization().getId(),
        null,
        toResponse(saved));
    return toSecretResponse(saved, plaintextKey);
  }

  @Override
  public Page<EnvironmentResponse> listByProject(UUID projectId, Pageable pageable) {
    permissionService.check(Action.ENV_READ, PermissionService.ResourceRef.project(projectId));
    return environmentRepository.findAllByProjectId(projectId, pageable).map(this::toResponse);
  }

  @Override
  public EnvironmentResponse get(UUID id) {
    Environment env = findById(id);
    permissionService.check(
        Action.ENV_READ, PermissionService.ResourceRef.project(env.getProject().getId()));
    return toResponse(env);
  }

  @Override
  @Transactional
  public EnvironmentResponse update(UUID id, UpdateEnvironmentRequest request) {
    Environment env = findById(id);
    permissionService.check(
        Action.ENV_UPDATE, PermissionService.ResourceRef.project(env.getProject().getId()));

    // Weakening protection attributes must not be possible below OWNER.
    boolean changingType = request.getType() != null && request.getType() != env.getType();
    boolean changingWindow =
        (request.getChangeWindowStartHour() != null
                && !Objects.equals(
                    request.getChangeWindowStartHour(), env.getChangeWindowStartHour()))
            || (request.getChangeWindowEndHour() != null
                && !Objects.equals(request.getChangeWindowEndHour(), env.getChangeWindowEndHour()));
    if (changingType || changingWindow) {
      permissionService.check(
          Action.ENV_MANAGE_PROTECTION,
          PermissionService.ResourceRef.project(env.getProject().getId()));
    }

    UUID orgId = env.getProject().getOrganization().getId();
    EnvironmentResponse before = toResponse(env);
    if (request.getName() != null) env.setName(request.getName());
    if (request.getDescription() != null) env.setDescription(request.getDescription());
    if (request.getType() != null) env.setType(request.getType());
    if (request.getChangeWindowStartHour() != null) {
      env.setChangeWindowStartHour(request.getChangeWindowStartHour());
    }
    if (request.getChangeWindowEndHour() != null) {
      env.setChangeWindowEndHour(request.getChangeWindowEndHour());
    }
    EnvironmentResponse after = toResponse(environmentRepository.save(env));
    auditService.record(AuditEntityType.ENVIRONMENT, id, AuditAction.UPDATE, orgId, before, after);
    return after;
  }

  @Override
  @Transactional
  public void delete(UUID id) {
    Environment env = findById(id);
    permissionService.check(
        Action.ENV_DELETE,
        PermissionService.ResourceRef.environment(env.getProject().getId(), env));
    UUID orgId = env.getProject().getOrganization().getId();
    EnvironmentResponse before = toResponse(env);
    environmentRepository.deleteById(id);
    auditService.record(AuditEntityType.ENVIRONMENT, id, AuditAction.DELETE, orgId, before, null);
  }

  @Override
  @Transactional
  public EnvironmentSecretResponse rotateApiKey(UUID id) {
    Environment env = findById(id);
    permissionService.check(
        Action.ENV_ROTATE_KEY,
        PermissionService.ResourceRef.environment(env.getProject().getId(), env));
    String plaintextKey = ApiKeyGenerator.generate();
    env.setApiKeyHash(ApiKeyHasher.hash(plaintextKey));
    Environment saved = environmentRepository.save(env);
    eventPublisher.publishEvent(
        new ApiKeyRotatedEvent(
            saved.getId(),
            saved.getName(),
            saved.getProject().getName(),
            permissionService.currentUserEmail()));
    // Record the rotation event only — never the key (before/after intentionally null).
    auditService.record(
        AuditEntityType.API_KEY,
        id,
        AuditAction.ROTATE_API_KEY,
        saved.getProject().getOrganization().getId(),
        null,
        null);
    return toSecretResponse(saved, plaintextKey);
  }

  /**
   * Gives every flag already in the project a state row in the new environment.
   *
   * <p>Mirror image of {@code FeatureFlagServiceImpl.create()}, which does the same in the other
   * direction for every existing environment. The invariant both sides maintain is that each (flag,
   * environment) pair has exactly one row: the SDK reads flags <em>through</em> that table, so a
   * flag with no row for this environment is simply absent from {@code GET /api/v1/sdk/flags} and
   * 404s on the single-flag endpoint — silently, with no error anywhere to explain it. The admin
   * API cannot rescue it either, since updating a state requires the row to exist.
   *
   * <p>Archived flags are included. They are still flags in the project, and skipping them would
   * reopen the same gap the moment one is unarchived.
   *
   * <p>New rows start disabled. An environment never inherits another environment's values —
   * copying state is what {@code EnvironmentTransferServiceImpl.clone()} is for.
   */
  private void backfillFlagStates(Project project, Environment environment) {
    for (FeatureFlag flag : featureFlagRepository.findAllByProjectId(project.getId())) {
      flagStateRepository.save(
          FlagEnvironmentState.builder()
              .featureFlag(flag)
              .environment(environment)
              .enabled(false)
              .build());
    }
  }

  private Environment findById(UUID id) {
    return environmentRepository
        .findById(id)
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
        .createdAt(env.getCreatedAt())
        .build();
  }

  /**
   * Response for create/rotate: the same fields as {@link #toResponse} plus the one-time plaintext
   * key.
   */
  private EnvironmentSecretResponse toSecretResponse(Environment env, String plaintextKey) {
    return EnvironmentSecretResponse.builder()
        .id(env.getId())
        .name(env.getName())
        .description(env.getDescription())
        .projectId(env.getProject().getId())
        .apiKey(plaintextKey)
        .createdAt(env.getCreatedAt())
        .build();
  }
}
