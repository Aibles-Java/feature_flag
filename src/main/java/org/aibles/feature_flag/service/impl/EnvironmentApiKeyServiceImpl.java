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
    EnvironmentApiKey key = findKeyIn(environmentId, keyId);
    Environment env = key.getEnvironment();
    permissionService.check(
        Action.ENV_KEY_REVOKE,
        PermissionService.ResourceRef.environment(env.getProject().getId(), env));

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
   * Deliberately does not consult {@link #MAX_ACTIVE_KEYS_PER_ENVIRONMENT}: rotation replaces one
   * key with another, so an environment already at the cap must still be able to rotate — it cannot
   * free a slot without revoking the very key it is trying to rotate. During the grace window the
   * environment transiently holds one more active key than the cap; that count returns to its prior
   * value once the old key's grace period ends (or is revoked with graceHours=0).
   */
  @Override
  @Transactional
  public ApiKeySecretResponse rotate(UUID environmentId, UUID keyId, RotateApiKeyRequest request) {
    EnvironmentApiKey old = findKeyIn(environmentId, keyId);
    Environment env = old.getEnvironment();
    // ENV_ROTATE_KEY, not CREATE+REVOKE: one door, and rotation stays fully windowed.
    permissionService.check(
        Action.ENV_ROTATE_KEY,
        PermissionService.ResourceRef.environment(env.getProject().getId(), env));

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

    LocalDateTime now = LocalDateTime.now(clock);
    MintedKey minted =
        EnvironmentApiKeyFactory.mint(
            env, old.getName(), freshExpiry(old, now), permissionService.currentUserId());
    EnvironmentApiKey fresh = apiKeyRepository.save(minted.key());

    if (request.getGraceHours() == 0) {
      old.setRevokedAt(now);
    } else {
      old.setExpiresAt(now.plusHours(request.getGraceHours()));
      // The deadline just moved, so any threshold already warned about is stale — re-arm it.
      old.setExpiryNoticeSentDays(null);
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
        .lastUsedAt(key.getLastUsedAt())
        .createdBy(key.getCreatedBy())
        .createdAt(key.getCreatedAt())
        .active(key.isActive(clock))
        .build();
  }
}
