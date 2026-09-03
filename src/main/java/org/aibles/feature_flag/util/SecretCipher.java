package org.aibles.feature_flag.util;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Reversible encryption for secrets that must be recoverable in plaintext (issue #36).
 *
 * <p><strong>Why not hash, like {@link ApiKeyHasher}?</strong> An SDK API key is only ever
 * <em>compared</em>, so a one-way SHA-256 is both sufficient and stronger. A webhook secret is
 * different in kind: it is the HMAC key used to sign every outgoing delivery, so the plaintext is
 * needed on each send. A hash cannot be un-hashed, so hashing a webhook secret would permanently
 * break signing. Encryption is the weaker-but-necessary choice here — do not "improve" it into a
 * hash. See {@code docs/adr/ADR-0005-webhook-delivery-and-secret-storage.md}.
 *
 * <p>AES-256-GCM: authenticated encryption, so tampering with stored ciphertext is detected on
 * decrypt rather than silently yielding a wrong key. A fresh random 96-bit IV is generated per
 * encryption — mandatory for GCM, where reusing an IV under the same key is catastrophic — and
 * prepended to the ciphertext. Output is {@code base64(iv || ciphertext || tag)}.
 */
public final class SecretCipher {

  /** GCM's recommended IV size. 96 bits lets the spec use the IV directly with no rehashing. */
  private static final int IV_BYTES = 12;

  private static final int TAG_BITS = 128;
  private static final String TRANSFORMATION = "AES/GCM/NoPadding";
  private static final SecureRandom RANDOM = new SecureRandom();

  private final SecretKeySpec key;

  /**
   * @param keyMaterial the configured secret; any length is accepted because it is folded to a
   *     256-bit key with SHA-256 (see {@link #deriveKey}), so callers never have to supply exactly
   *     32 bytes or worry about base64 decoding
   */
  public SecretCipher(String keyMaterial) {
    this.key = deriveKey(keyMaterial);
  }

  /**
   * Folds arbitrary-length key material to the 32 bytes AES-256 requires.
   *
   * <p>A plain SHA-256 rather than a password KDF (PBKDF2/argon2) on purpose: the configured value
   * is a high-entropy random string (`openssl rand -hex 64`), validated for length and entropy at
   * startup by {@code WebhookProperties}, so there is no low-entropy password to stretch — the same
   * reasoning {@link ApiKeyHasher} documents for skipping a salted slow hash.
   */
  private static SecretKeySpec deriveKey(String keyMaterial) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return new SecretKeySpec(digest.digest(keyMaterial.getBytes(StandardCharsets.UTF_8)), "AES");
    } catch (GeneralSecurityException e) {
      // SHA-256 is mandatory on every JVM.
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  /**
   * @return {@code base64(iv || ciphertext || tag)}
   */
  public String encrypt(String plaintext) {
    try {
      byte[] iv = new byte[IV_BYTES];
      RANDOM.nextBytes(iv);

      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

      byte[] combined = new byte[iv.length + ciphertext.length];
      System.arraycopy(iv, 0, combined, 0, iv.length);
      System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
      return Base64.getEncoder().encodeToString(combined);
    } catch (GeneralSecurityException e) {
      // Never include the exception message or plaintext — both can leak the secret.
      throw new IllegalStateException("Failed to encrypt secret", e);
    }
  }

  /**
   * @throws IllegalStateException if the value was not produced by {@link #encrypt} with this same
   *     key — GCM's authentication tag makes a wrong key or tampered ciphertext a hard failure
   *     rather than silent garbage
   */
  public String decrypt(String encoded) {
    try {
      byte[] combined = Base64.getDecoder().decode(encoded);
      if (combined.length <= IV_BYTES) {
        throw new IllegalStateException("Stored secret is too short to contain an IV");
      }
      byte[] iv = new byte[IV_BYTES];
      System.arraycopy(combined, 0, iv, 0, IV_BYTES);

      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      byte[] plaintext = cipher.doFinal(combined, IV_BYTES, combined.length - IV_BYTES);
      return new String(plaintext, StandardCharsets.UTF_8);
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      throw new IllegalStateException(
          "Failed to decrypt stored secret — wrong app.webhook.encryption-key, or the value was"
              + " tampered with",
          e);
    }
  }
}
