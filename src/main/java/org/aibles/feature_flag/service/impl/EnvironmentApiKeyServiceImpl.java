package org.aibles.feature_flag.service.impl;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.domain.entity.EnvironmentApiKey;
import org.aibles.feature_flag.domain.enums.Action;
import org.aibles.feature_flag.domain.enums.AuditAction;
import org.aibles.feature_flag.domain.enums.AuditEntityType;
import org.aibles.feature_flag.dto.request.CreateApiKeyRequest;
import org.aibles.feature_flag.dto.response.ApiKeyResponse;
import org.aibles.feature_flag.dto.response.ApiKeySecretResponse;
import org.aibles.feature_flag.exception.DuplicateResourceException;
import org.aibles.feature_flag.exception.ResourceNotFoundException;
import org.aibles.feature_flag.repository.EnvironmentApiKeyRepository;
import org.aibles.feature_flag.repository.EnvironmentRepository;
import org.aibles.feature_flag.service.EnvironmentApiKeyService;
import org.aibles.feature_flag.util.EnvironmentApiKeyFactory;
import org.aibles.feature_flag.util.EnvironmentApiKeyFactory.MintedKey;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Create/list/revoke for the per-environment SDK keys minted by {@link EnvironmentApiKeyFactory}.
 * Rotation (task 5) will extend this service rather than replace it.
 */
@Service
@RequiredArgsConstructor
public class EnvironmentApiKeyServiceImpl implements EnvironmentApiKeyService {

  public static final int MAX_ACTIVE_KEYS_PER_ENVIRONMENT = 10;

  private final EnvironmentApiKeyRepository apiKeyRepository;
  private final EnvironmentRepository environmentRepository;
  private final PermissionService permissionService;
  private final AuditService auditService;
  private final Clock clock;

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
            env, request.getName(), request.getExpiresAt(), permissionService.currentUserId());
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
