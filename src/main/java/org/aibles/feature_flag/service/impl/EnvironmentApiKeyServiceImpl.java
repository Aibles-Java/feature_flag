package org.aibles.feature_flag.service.impl;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.aibles.feature_flag.config.ApiKeyProperties;
import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.domain.entity.EnvironmentApiKey;
import org.aibles.feature_flag.domain.enums.Action;
import org.aibles.feature_flag.domain.enums.AuditAction;
import org.aibles.feature_flag.domain.enums.AuditEntityType;
import org.aibles.feature_flag.dto.request.CreateApiKeyRequest;
import org.aibles.feature_flag.dto.request.RotateApiKeyRequest;
import org.aibles.feature_flag.dto.response.ApiKeyResponse;
import org.aibles.feature_flag.dto.response.ApiKeySecretResponse;
import org.aibles.feature_flag.exception.DuplicateResourceException;
import org.aibles.feature_flag.exception.ResourceNotFoundException;
import org.aibles.feature_flag.notification.event.ApiKeyRotatedEvent;
import org.aibles.feature_flag.repository.EnvironmentApiKeyRepository;
import org.aibles.feature_flag.repository.EnvironmentRepository;
import org.aibles.feature_flag.service.EnvironmentApiKeyService;
import org.aibles.feature_flag.util.EnvironmentApiKeyFactory;
import org.aibles.feature_flag.util.EnvironmentApiKeyFactory.MintedKey;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Create/list/revoke/rotate for the per-environment SDK keys minted by {@link
 * EnvironmentApiKeyFactory}. Deliberately does not depend on {@code EnvironmentService} — the
 * legacy env-level rotate endpoint depends on this service instead, and a dependency the other way
 * would create a circular bean graph.
 */
@Service
@RequiredArgsConstructor
public class EnvironmentApiKeyServiceImpl implements EnvironmentApiKeyService {

  public static final int MAX_ACTIVE_KEYS_PER_ENVIRONMENT = 10;

  private final EnvironmentApiKeyRepository apiKeyRepository;
  private final EnvironmentRepository environmentRepository;
  private final PermissionService permissionService;
  private final ApplicationEventPublisher eventPublisher;
  private final AuditService auditService;
  private final Clock clock;
  private final ApiKeyProperties apiKeyProperties;

  @Override
  @Transactional
  public ApiKeySecretResponse create(UUID environmentId, CreateApiKeyRequest request) {
    Environment env = findEnvironment(environmentId);
    // The environment must ride on the ResourceRef: without it the production rules cannot
    // see that this is a production credential and rule B never fires.
    permissionService.check(
        Action.ENV_KEY_CREATE,
        PermissionService.ResourceRef.environment(env.getProject().getId(), env));

    LocalDateTime now = LocalDateTime.now(clock);
    if (apiKeyRepository.countActiveByEnvironmentId(environmentId, now)
        >= MAX_ACTIVE_KEYS_PER_ENVIRONMENT) {
      throw new DuplicateResourceException(
          "Environment has reached the maximum of "
              + MAX_ACTIVE_KEYS_PER_ENVIRONMENT
              + " active API keys");
    }

    MintedKey minted =
        EnvironmentApiKeyFactory.mint(
            env, request.getName(), resolveExpiry(request, now), permissionService.currentUserId());
    EnvironmentApiKey saved = apiKeyRepository.save(minted.key());

    // before/after stay null: the ledger records that a key event happened, never the key.
    auditService.record(
        AuditEntityType.API_KEY,
        saved.getId(),
        AuditAction.CREATE_API_KEY,
        env.getProject().getOrganization().getId(),
        null,
        null);

    return ApiKeySecretResponse.builder().key(toResponse(saved)).apiKey(minted.plaintext()).build();
  }

  /**
   * The stored expiry for a new key: the caller's explicit deadline, {@code null} when the caller
   * asked for a key that never expires, otherwise the configured default lifetime. The request DTO
   * rejects {@code expiresAt} combined with {@code neverExpires} before this runs.
   */
  private LocalDateTime resolveExpiry(CreateApiKeyRequest request, LocalDateTime now) {
    if (request.getExpiresAt() != null) {
      return request.getExpiresAt();
    }
    if (request.isNeverExpires()) {
      return null;
    }
    return now.plus(apiKeyProperties.defaultTtl());
  }

  @Override
  public Page<ApiKeyResponse> list(UUID environmentId, Pageable pageable) {
    Environment env = findEnvironment(environmentId);
    permissionService.check(
        Action.ENV_READ, PermissionService.ResourceRef.project(env.getProject().getId()));
    return apiKeyRepository.findAllByEnvironmentId(environmentId, pageable).map(this::toResponse);
  }

  @Override
  @Transactional
  public void revoke(UUID environmentId, UUID keyId) {
    // Authorize on the environment before looking the key up, so a caller without access cannot
    // tell a key of this environment (403) from any other id (404).
    Environment env = findEnvironment(environmentId);
    permissionService.check(
        Action.ENV_KEY_REVOKE,
        PermissionService.ResourceRef.environment(env.getProject().getId(), env));
    EnvironmentApiKey key = findKeyIn(environmentId, keyId);

    if (key.isRevoked()) {
      throw new DuplicateResourceException("API key is already revoked");
    }
    key.setRevokedAt(LocalDateTime.now(clock));
    apiKeyRepository.save(key);

    auditService.record(
        AuditEntityType.API_KEY,
        keyId,
        AuditAction.REVOKE_API_KEY,
        env.getProject().getOrganization().getId(),
        null,
        null);
  }

  /**
   * Replaces a key with a new one. Three rules keep a grace period from becoming a way to extend a
   * credential's life:
   *
   * <ul>
   *   <li>A key can be rotated <b>once</b>. The replaced key is stamped {@code rotatedAt}; a second
   *       rotation of it is refused, so a key kept alive by a grace period cannot be rotated again
   *       to push its deadline out indefinitely. Rotate the replacement instead.
   *   <li>The grace deadline never extends the old key: it is {@code min(expiresAt, now + grace)}.
   *   <li>An environment already at {@link #MAX_ACTIVE_KEYS_PER_ENVIRONMENT} may still rotate — it
   *       cannot free a slot without revoking the key it is rotating — and so may briefly hold one
   *       key over the cap during a grace period, but never more than one.
   * </ul>
   */
  @Override
  @Transactional
  public ApiKeySecretResponse rotate(UUID environmentId, UUID keyId, RotateApiKeyRequest request) {
    Environment env = findEnvironment(environmentId);
    // ENV_ROTATE_KEY, not CREATE+REVOKE: one door, and rotation stays fully windowed. Checked
    // before the key lookup for the same reason as revoke().
    permissionService.check(
        Action.ENV_ROTATE_KEY,
        PermissionService.ResourceRef.environment(env.getProject().getId(), env));
    EnvironmentApiKey old = findKeyIn(environmentId, keyId);

    // isRevoked()/isExpired() checked separately, not old.isActive(clock) collapsed into one
    // branch, so the 409 message tells an operator which of the two dead states they hit.
    if (old.isRevoked()) {
      throw new DuplicateResourceException("API key is already revoked");
    }
    if (old.isExpired(clock)) {
      // Must reject before minting: falling through to the graceHours>0 branch below would
      // push this key's already-past expiresAt into the future, resurrecting a dead credential.
      throw new DuplicateResourceException("API key has already expired");
    }
    if (old.getRotatedAt() != null) {
      throw new DuplicateResourceException(
          "API key has already been rotated; rotate its replacement instead");
    }

    LocalDateTime now = LocalDateTime.now(clock);
    // The old key stays active through a grace period, so this rotation adds one active key.
    // Allow that once over the cap (see the Javadoc), never beyond it.
    if (request.getGraceHours() > 0
        && apiKeyRepository.countActiveByEnvironmentId(environmentId, now)
            > MAX_ACTIVE_KEYS_PER_ENVIRONMENT) {
      throw new DuplicateResourceException(
          "Environment is over the maximum of "
              + MAX_ACTIVE_KEYS_PER_ENVIRONMENT
              + " active API keys; rotate with graceHours=0 or revoke a key first");
    }
    MintedKey minted =
        EnvironmentApiKeyFactory.mint(
            env, old.getName(), freshExpiry(old, now), permissionService.currentUserId());
    EnvironmentApiKey fresh = apiKeyRepository.save(minted.key());

    old.setRotatedAt(now);
    if (request.getGraceHours() == 0) {
      old.setRevokedAt(now);
    } else {
      // Never later than the key's own deadline: a grace period shortens a key's life, it does
      // not extend it.
      LocalDateTime graceDeadline = now.plusHours(request.getGraceHours());
      if (old.getExpiresAt() == null || graceDeadline.isBefore(old.getExpiresAt())) {
        old.setExpiresAt(graceDeadline);
        // The deadline just moved, so any threshold already warned about is stale — re-arm it.
        old.setExpiryNoticeSentDays(null);
      }
    }
    apiKeyRepository.save(old);

    eventPublisher.publishEvent(
        new ApiKeyRotatedEvent(
            env.getId(),
            env.getName(),
            env.getProject().getName(),
            permissionService.currentUserEmail()));
    // before/after stay null: the ledger records that a key event happened, never the key.
    auditService.record(
        AuditEntityType.API_KEY,
        fresh.getId(),
        AuditAction.ROTATE_API_KEY,
        env.getProject().getOrganization().getId(),
        null,
        null);

    return ApiKeySecretResponse.builder().key(toResponse(fresh)).apiKey(minted.plaintext()).build();
  }

  /**
   * A rotated key gets a fresh lifetime of the same length as the key it replaces — a 30-day key
   * rotates into a 30-day key, a never-expiring key into a never-expiring key. Inheriting the old
   * deadline instead would make rotation useless against an expiring key: the replacement would die
   * on the same day the expiry warning was about.
   */
  private static LocalDateTime freshExpiry(EnvironmentApiKey old, LocalDateTime now) {
    if (old.getExpiresAt() == null) {
      return null;
    }
    return now.plus(Duration.between(old.getCreatedAt(), old.getExpiresAt()));
  }

  private Environment findEnvironment(UUID id) {
    return environmentRepository
        .findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Environment", id));
  }

  /**
   * Resolves a key and asserts it belongs to the named environment. A key from another environment
   * is reported as not found, not as forbidden: a 403 would confirm to someone guessing ids that
   * the key exists somewhere.
   */
  private EnvironmentApiKey findKeyIn(UUID environmentId, UUID keyId) {
    EnvironmentApiKey key =
        apiKeyRepository
            .findById(keyId)
            .orElseThrow(() -> new ResourceNotFoundException("ApiKey", keyId));
    if (!key.getEnvironment().getId().equals(environmentId)) {
      throw new ResourceNotFoundException("ApiKey", keyId);
    }
    return key;
  }

  private ApiKeyResponse toResponse(EnvironmentApiKey key) {
    return ApiKeyResponse.builder()
        .id(key.getId())
        .environmentId(key.getEnvironment().getId())
        .name(key.getName())
        .keyPrefix(key.getKeyPrefix())
        .expiresAt(key.getExpiresAt())
        .revokedAt(key.getRevokedAt())
        .rotatedAt(key.getRotatedAt())
        .lastUsedAt(key.getLastUsedAt())
        .createdBy(key.getCreatedBy())
        .createdAt(key.getCreatedAt())
        .active(key.isActive(clock))
        .build();
  }
}
