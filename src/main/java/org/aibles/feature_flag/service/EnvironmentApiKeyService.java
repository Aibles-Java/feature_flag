package org.aibles.feature_flag.service;

import java.util.UUID;
import org.aibles.feature_flag.dto.request.CreateApiKeyRequest;
import org.aibles.feature_flag.dto.request.RotateApiKeyRequest;
import org.aibles.feature_flag.dto.response.ApiKeyResponse;
import org.aibles.feature_flag.dto.response.ApiKeySecretResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface EnvironmentApiKeyService {

  /** Returns the plaintext API key exactly once — see {@link ApiKeySecretResponse}. */
  ApiKeySecretResponse create(UUID environmentId, CreateApiKeyRequest request);

  Page<ApiKeyResponse> list(UUID environmentId, Pageable pageable);

  void revoke(UUID environmentId, UUID keyId);

  /**
   * Mints a replacement key and gives the old one a deadline instead of killing it, so an SDK fleet
   * can be redeployed before the old credential stops working. {@code request.graceHours() == 0}
   * revokes the old key immediately.
   */
  ApiKeySecretResponse rotate(UUID environmentId, UUID keyId, RotateApiKeyRequest request);
}
