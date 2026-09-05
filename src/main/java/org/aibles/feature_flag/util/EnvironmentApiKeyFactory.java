package org.aibles.feature_flag.util;

import java.time.LocalDateTime;
import java.util.UUID;
import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.domain.entity.EnvironmentApiKey;

/**
 * Builds a key row and its one-time plaintext together. Centralised because three call sites mint
 * keys — environment creation, environment cloning and the key API — and all three must derive the
 * stored prefix from the same plaintext they hand back, or the prefix stops identifying the key.
 */
public final class EnvironmentApiKeyFactory {

  /** The name given to the key an environment is born with. */
  public static final String DEFAULT_KEY_NAME = "default";

  private static final int PREFIX_LENGTH = 8;

  private EnvironmentApiKeyFactory() {}

  /** A key row paired with the plaintext that is returned to the caller exactly once. */
  public record MintedKey(EnvironmentApiKey key, String plaintext) {}

  public static MintedKey mint(
      Environment environment, String name, LocalDateTime expiresAt, UUID createdBy) {
    String plaintext = ApiKeyGenerator.generate();
    EnvironmentApiKey key =
        EnvironmentApiKey.builder()
            .environment(environment)
            .name(name)
            .keyHash(ApiKeyHasher.hash(plaintext))
            .keyPrefix(plaintext.substring(0, PREFIX_LENGTH))
            .expiresAt(expiresAt)
            .createdBy(createdBy)
            .build();
    return new MintedKey(key, plaintext);
  }
}
