package org.aibles.feature_flag.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

/**
 * Returned <strong>only</strong> on environment creation and API-key rotation. Carries the
 * plaintext {@code apiKey} exactly once — it is never stored (only its SHA-256 hash is) and cannot
 * be retrieved again, so the caller must capture it now. All read endpoints return the secret-free
 * {@link EnvironmentResponse}.
 */
@Data
@Builder
public class EnvironmentSecretResponse {
  private UUID id;
  private String name;
  private String description;
  private UUID projectId;
  private String apiKey;
  private LocalDateTime createdAt;
}
