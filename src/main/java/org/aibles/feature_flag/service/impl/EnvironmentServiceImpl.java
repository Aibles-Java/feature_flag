package org.aibles.feature_flag.service.impl;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.domain.entity.Project;
import org.aibles.feature_flag.domain.enums.AuditAction;
import org.aibles.feature_flag.domain.enums.AuditEntityType;
import org.aibles.feature_flag.domain.enums.MemberRole;
import org.aibles.feature_flag.dto.request.CreateEnvironmentRequest;
import org.aibles.feature_flag.dto.request.UpdateEnvironmentRequest;
import org.aibles.feature_flag.dto.response.EnvironmentResponse;
import org.aibles.feature_flag.dto.response.EnvironmentSecretResponse;
import org.aibles.feature_flag.exception.DuplicateResourceException;
import org.aibles.feature_flag.exception.ResourceNotFoundException;
import org.aibles.feature_flag.notification.event.ApiKeyRotatedEvent;
import org.aibles.feature_flag.repository.EnvironmentRepository;
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

  @Override
  @Transactional
  public EnvironmentSecretResponse create(CreateEnvironmentRequest request) {
    permissionService.requireRoleForProject(
        request.getProjectId(), MemberRole.OWNER, MemberRole.ADMIN);
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
            .apiKeyHash(ApiKeyHasher.hash(plaintextKey))
            .build();
    Environment saved = environmentRepository.save(env);
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
    permissionService.requireRoleForProject(
        projectId, MemberRole.OWNER, MemberRole.ADMIN, MemberRole.VIEWER);
    return environmentRepository.findAllByProjectId(projectId, pageable).map(this::toResponse);
  }

  @Override
  public EnvironmentResponse get(UUID id) {
    Environment env = findById(id);
    permissionService.requireRoleForEnvironment(
        id, MemberRole.OWNER, MemberRole.ADMIN, MemberRole.VIEWER);
    return toResponse(env);
  }

  @Override
  @Transactional
  public EnvironmentResponse update(UUID id, UpdateEnvironmentRequest request) {
    permissionService.requireRoleForEnvironment(id, MemberRole.OWNER, MemberRole.ADMIN);
    Environment env = findById(id);
    UUID orgId = env.getProject().getOrganization().getId();
    EnvironmentResponse before = toResponse(env);
    if (request.getName() != null) env.setName(request.getName());
    if (request.getDescription() != null) env.setDescription(request.getDescription());
    EnvironmentResponse after = toResponse(environmentRepository.save(env));
    auditService.record(AuditEntityType.ENVIRONMENT, id, AuditAction.UPDATE, orgId, before, after);
    return after;
  }

  @Override
  @Transactional
  public void delete(UUID id) {
    permissionService.requireRoleForEnvironment(id, MemberRole.OWNER);
    Environment env = findById(id);
    UUID orgId = env.getProject().getOrganization().getId();
    EnvironmentResponse before = toResponse(env);
    environmentRepository.deleteById(id);
    auditService.record(AuditEntityType.ENVIRONMENT, id, AuditAction.DELETE, orgId, before, null);
  }

  @Override
  @Transactional
  public EnvironmentSecretResponse rotateApiKey(UUID id) {
    permissionService.requireRoleForEnvironment(id, MemberRole.OWNER, MemberRole.ADMIN);
    Environment env = findById(id);
    String plaintextKey = ApiKeyGenerator.generate();
    env.setApiKeyHash(ApiKeyHasher.hash(plaintextKey));
    Environment saved = environmentRepository.save(env);
    eventPublisher.publishEvent(
        new ApiKeyRotatedEvent(
            saved.getName(), saved.getProject().getName(), permissionService.currentUserEmail()));
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
