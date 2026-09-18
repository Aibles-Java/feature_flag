package org.aibles.feature_flag.dto.response;

import lombok.Builder;
import lombok.Data;

/**
 * Returned <strong>only</strong> on key creation and rotation. Carries the plaintext exactly once —
 * it is never stored (only its SHA-256 hash is) and cannot be retrieved again, so the caller must
 * capture it now. Every read endpoint returns the secret-free {@link ApiKeyResponse}.
 */
@Data
@Builder
public class ApiKeySecretResponse {
  private ApiKeyResponse key;
  private String apiKey;
}
