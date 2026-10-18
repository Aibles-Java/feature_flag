package org.aibles.feature_flag.service.impl;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.domain.entity.EnvironmentApiKey;
import org.aibles.feature_flag.domain.entity.FlagEnvironmentState;
import org.aibles.feature_flag.domain.entity.Project;
import org.aibles.feature_flag.domain.enums.Action;
import org.aibles.feature_flag.domain.enums.AuditAction;
import org.aibles.feature_flag.domain.enums.AuditEntityType;
import org.aibles.feature_flag.domain.enums.EnvType;
import org.aibles.feature_flag.dto.request.CreateEnvironmentRequest;
import org.aibles.feature_flag.dto.request.RotateApiKeyRequest;
import org.aibles.feature_flag.dto.request.UpdateEnvironmentRequest;
import org.aibles.feature_flag.dto.response.ApiKeySecretResponse;
import org.aibles.feature_flag.dto.response.EnvironmentResponse;
import org.aibles.feature_flag.dto.response.EnvironmentSecretResponse;
import org.aibles.feature_flag.exception.DuplicateResourceException;
import org.aibles.feature_flag.exception.ResourceNotFoundException;
import org.aibles.feature_flag.repository.EnvironmentApiKeyRepository;
import org.aibles.feature_flag.repository.EnvironmentRepository;
import org.aibles.feature_flag.repository.FeatureFlagRepository;
import org.aibles.feature_flag.repository.FlagEnvironmentStateRepository;
import org.aibles.feature_flag.repository.ProjectRepository;
import org.aibles.feature_flag.service.EnvironmentApiKeyService;
import org.aibles.feature_flag.service.EnvironmentService;
import org.aibles.feature_flag.service.EvaluationCacheService;
import org.aibles.feature_flag.util.EnvironmentApiKeyFactory;
import org.aibles.feature_flag.util.EnvironmentApiKeyFactory.MintedKey;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EnvironmentServiceImpl implements EnvironmentService {

  private final EnvironmentRepository environmentRepository;
  private final EnvironmentApiKeyRepository apiKeyRepository;
  private final ProjectRepository projectRepository;
  private final PermissionService permissionService;
  // NOT EnvironmentApiKeyServiceImpl injecting this class back: that would be a circular bean
  // dependency. This is a one-way dependency onto the key service, used only by the legacy
  // env-level rotate endpoint below.
  private final EnvironmentApiKeyService apiKeyService;
  private final EvaluationCacheService evaluationCacheService;
  private final AuditService auditService;
  private final Clock clock;
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

    Environment env =
        Environment.builder()
            .project(project)
            .name(request.getName())
            .description(request.getDescription())
            .type(request.getType() != null ? request.getType() : EnvType.DEVELOPMENT)
            .changeWindowStartHour(request.getChangeWindowStartHour())
            .changeWindowEndHour(request.getChangeWindowEndHour())
            .changeWindowTimezone(request.getChangeWindowTimezone())
            .build();
    Environment saved = environmentRepository.save(env);
    MintedKey minted =
        EnvironmentApiKeyFactory.mint(
            saved,
            EnvironmentApiKeyFactory.DEFAULT_KEY_NAME,
            null,
            permissionService.currentUserId());
    apiKeyRepository.save(minted.key());
    backfillFlagStates(project, saved);
    // Audit the non-secret view only — never the plaintext key.
    auditService.record(
        AuditEntityType.ENVIRONMENT,
        saved.getId(),
        AuditAction.CREATE,
        project.getOrganization().getId(),
        null,
        toSnapshot(saved));
    return toSecretResponse(saved, minted.plaintext());
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
                && !Objects.equals(request.getChangeWindowEndHour(), env.getChangeWindowEndHour()))
            // The zone moves the window as surely as the hours do: offsets span ~26h, so an
            // unguarded zone change can slide any wall-clock hour into (or out of) the window.
            || (request.getChangeWindowTimezone() != null
                && !Objects.equals(
                    request.getChangeWindowTimezone(), env.getChangeWindowTimezone()));
    if (changingType || changingWindow) {
      permissionService.check(
          Action.ENV_MANAGE_PROTECTION,
          PermissionService.ResourceRef.project(env.getProject().getId()));
    }

    UUID orgId = env.getProject().getOrganization().getId();
    EnvironmentResponse before = toSnapshot(env);
    if (request.getName() != null) env.setName(request.getName());
    if (request.getDescription() != null) env.setDescription(request.getDescription());
    if (request.getType() != null) env.setType(request.getType());
    if (request.getChangeWindowStartHour() != null) {
      env.setChangeWindowStartHour(request.getChangeWindowStartHour());
    }
    if (request.getChangeWindowEndHour() != null) {
      env.setChangeWindowEndHour(request.getChangeWindowEndHour());
    }
    if (request.getChangeWindowTimezone() != null) {
      env.setChangeWindowTimezone(request.getChangeWindowTimezone());
    }
    Environment saved = environmentRepository.save(env);
    EnvironmentResponse after = toSnapshot(saved);
    auditService.record(AuditEntityType.ENVIRONMENT, id, AuditAction.UPDATE, orgId, before, after);
    return toResponse(saved);
  }

  @Override
  @Transactional
  public void delete(UUID id) {
    Environment env = findById(id);
    permissionService.check(
        Action.ENV_DELETE,
        PermissionService.ResourceRef.environment(env.getProject().getId(), env));
    UUID orgId = env.getProject().getOrganization().getId();
    EnvironmentResponse before = toSnapshot(env);
    environmentRepository.deleteById(id);
    evaluationCacheService.evictAfterCommit(id);
    auditService.record(AuditEntityType.ENVIRONMENT, id, AuditAction.DELETE, orgId, before, null);
  }

  @Override
  @Transactional
  public EnvironmentSecretResponse rotateApiKey(UUID id) {
    Environment env = findById(id);
    // Authorize before resolving or revealing anything below: an unauthorized caller must not
    // learn how many active keys this environment holds (or that it has any at all) from the
    // 409 message that follows.
    permissionService.check(
        Action.ENV_ROTATE_KEY,
        PermissionService.ResourceRef.environment(env.getProject().getId(), env));
    List<EnvironmentApiKey> active =
        apiKeyRepository.findActiveByEnvironmentId(id, LocalDateTime.now(clock));
    // "The" key is only meaningful while there is exactly one. With several, the caller has
    // to say which — silently picking one would revoke a credential they did not name.
    if (active.size() != 1) {
      throw new DuplicateResourceException(
          "Environment has "
              + active.size()
              + " active API keys; use POST /api/v1/environments/{envId}/api-keys/{keyId}/rotate");
    }
    // apiKeyService.rotate() re-checks ENV_ROTATE_KEY below — intentional duplication, not an
    // oversight: it is a pure predicate re-run on an already-loaded environment, and removing
    // either check would leave a path whose safety depends on the other method never being
    // called directly.
    ApiKeySecretResponse rotated =
        apiKeyService.rotate(id, active.get(0).getId(), new RotateApiKeyRequest());
    return toSecretResponse(env, rotated.getApiKey());
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
    // One saveAll inside the caller's transaction (JDBC batching is not configured, so this is
    // one call, not one statement) instead of a save per flag: bounded at the project's flag count
    // (<= 1 000 per ADR-03). Defaults are explicit —
    // disabled, rolloutPercent 100, no value — so a new (possibly PRODUCTION) environment never
    // starts live. The optimistic-lock version column (S-2.1) is deliberately not set here.
    List<FlagEnvironmentState> states =
        featureFlagRepository.findAllByProjectId(project.getId()).stream()
            .map(
                flag ->
                    FlagEnvironmentState.builder()
                        .featureFlag(flag)
                        .environment(environment)
                        .enabled(false)
                        .rolloutPercent(100)
                        .build())
            .toList();
    if (!states.isEmpty()) {
      flagStateRepository.saveAll(states);
    }
  }

  private Environment findById(UUID id) {
    return environmentRepository
        .findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Environment", id));
  }

  /** Response with the live change-window state (S-2.11); never use for audit snapshots. */
  private EnvironmentResponse toResponse(Environment env) {
    EnvironmentResponse response = toSnapshot(env);
    response.setChangeWindowZone(permissionService.changeWindowZone(env).getId());
    response.setChangeWindowOpenNow(permissionService.withinChangeWindow(env));
    return response;
  }

  /**
   * Stored attributes only. Audit before/after snapshots use this so a time-dependent open/closed
   * flag never shows up as a spurious diff.
   */
  private EnvironmentResponse toSnapshot(Environment env) {
    return EnvironmentResponse.builder()
        .id(env.getId())
        .name(env.getName())
        .description(env.getDescription())
        .projectId(env.getProject().getId())
        .type(env.getType())
        .changeWindowStartHour(env.getChangeWindowStartHour())
        .changeWindowEndHour(env.getChangeWindowEndHour())
        .changeWindowTimezone(env.getChangeWindowTimezone())
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
