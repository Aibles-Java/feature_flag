package org.aibles.feature_flag.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

/** The secret-free view of a key. Never carries the plaintext or the hash. */
@Data
@Builder
public class ApiKeyResponse {
  private UUID id;
  private UUID environmentId;
  private String name;

  /**
   * First 8 characters of the plaintext — how an operator identifies the key. Empty for keys
   * migrated from the single-key era.
   */
  private String keyPrefix;

  private LocalDateTime expiresAt;
  private LocalDateTime revokedAt;
  private LocalDateTime lastUsedAt;
  private UUID createdBy;
  private LocalDateTime createdAt;

  /** Derived: not revoked and not past its expiry. */
  private boolean active;
}
