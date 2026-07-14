package org.aibles.feature_flag.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Hashes SDK API keys for storage at rest (issue #24).
 *
 * <p>API keys are 256-bit high-entropy random values (see {@link ApiKeyGenerator}), so a plain
 * unsalted SHA-256 is sufficient — a salt/slow-hash (bcrypt/argon2) is only needed for low-entropy
 * secrets like passwords, and would also break the O(1) indexed lookup by hash.
 *
 * <p>The digest is computed over the UTF-8 bytes of the key and rendered as lowercase hex, so it
 * matches Postgres {@code encode(digest(key, 'sha256'), 'hex')} used by the backfill migration —
 * existing plaintext keys hashed in the DB authenticate identically here.
 */
public final class ApiKeyHasher {

  private ApiKeyHasher() {}

  public static String hash(String apiKey) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(apiKey.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash); // 64-char lowercase hex
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is a mandatory algorithm on every JVM; this can never happen.
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}
