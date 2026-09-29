package org.aibles.feature_flag.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SecretCipherTest {

  private static final String KEY =
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
  private final SecretCipher cipher = new SecretCipher(KEY);

  @Test
  void roundTripsASecret() {
    String secret = "whsec_9f8a7b6c5d4e3f2a1b0c";

    assertThat(cipher.decrypt(cipher.encrypt(secret))).isEqualTo(secret);
  }

  @ParameterizedTest(name = "round-trips \"{0}\"")
  @ValueSource(strings = {"", "a", "sécret-ü-🔑", "  spaces  "})
  void roundTripsEdgeCaseValues(String secret) {
    assertThat(cipher.decrypt(cipher.encrypt(secret))).isEqualTo(secret);
  }

  /**
   * GCM is catastrophically broken by IV reuse, so every encryption must draw a fresh IV — which
   * also means the same plaintext must never produce the same ciphertext twice.
   */
  @Test
  @DisplayName("encrypting the same secret twice yields different ciphertext (fresh IV)")
  void usesAFreshIvPerEncryption() {
    Set<String> ciphertexts = new HashSet<>();
    for (int i = 0; i < 100; i++) {
      ciphertexts.add(cipher.encrypt("same-secret"));
    }

    assertThat(ciphertexts).hasSize(100);
  }

  @Test
  void ciphertextDoesNotContainThePlaintext() {
    String encrypted = cipher.encrypt("whsec_super_secret_value");

    assertThat(encrypted).doesNotContain("whsec_super_secret_value");
    assertThat(new String(Base64.getDecoder().decode(encrypted)))
        .doesNotContain("whsec_super_secret_value");
  }

  @Test
  @DisplayName("a different key cannot decrypt — GCM fails loudly instead of returning garbage")
  void rejectsWrongKey() {
    String encrypted = cipher.encrypt("whsec_value");
    SecretCipher other = new SecretCipher(KEY + "-different");

    assertThatThrownBy(() -> other.decrypt(encrypted))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Failed to decrypt");
  }

  @Test
  @DisplayName("tampered ciphertext is rejected by the authentication tag")
  void rejectsTamperedCiphertext() {
    byte[] raw = Base64.getDecoder().decode(cipher.encrypt("whsec_value"));
    raw[raw.length - 1] ^= 0x01; // flip one bit in the tag
    String tampered = Base64.getEncoder().encodeToString(raw);

    assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
  }

  @ParameterizedTest(name = "rejects malformed stored value \"{0}\"")
  @ValueSource(strings = {"", "not-base64!!", "c2hvcnQ="})
  void rejectsMalformedStoredValue(String stored) {
    assertThatThrownBy(() -> cipher.decrypt(stored)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("the failure message never echoes the secret or the key")
  void failureMessageLeaksNothing() {
    String encrypted = cipher.encrypt("whsec_leak_me");
    SecretCipher other = new SecretCipher("some-other-key-material-entirely");

    assertThatThrownBy(() -> other.decrypt(encrypted))
        .hasMessageNotContaining("whsec_leak_me")
        .hasMessageNotContaining(KEY);
  }
}
