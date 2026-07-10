package org.aibles.feature_flag.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ApiKeyHasher}. The hash must be deterministic and match the standard
 * SHA-256 hex digest, so the Postgres backfill ({@code encode(digest(x,'sha256'),'hex')}) and the
 * application agree on the stored value.
 */
class ApiKeyHasherTest {

  @Test
  void producesKnownSha256Vector() {
    // NIST SHA-256("abc") — the canonical test vector, lowercase hex.
    assertThat(ApiKeyHasher.hash("abc"))
        .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  }

  @Test
  void isDeterministic() {
    assertThat(ApiKeyHasher.hash("some-api-key")).isEqualTo(ApiKeyHasher.hash("some-api-key"));
  }

  @Test
  void producesLowercase64CharHex() {
    String hash = ApiKeyHasher.hash(ApiKeyGenerator.generate());
    assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
  }

  @Test
  void differentKeysProduceDifferentHashes() {
    assertThat(ApiKeyHasher.hash("key-one")).isNotEqualTo(ApiKeyHasher.hash("key-two"));
  }
}
