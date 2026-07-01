package org.aibles.feature_flag.util;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ApiKeyGenerator}: format, length, and uniqueness of the
 * SecureRandom-backed 32-byte hex keys used for environment authentication.
 */
class ApiKeyGeneratorTest {

    @Test
    void generatesSixtyFourCharLowercaseHex() {
        String key = ApiKeyGenerator.generate();

        assertThat(key).hasSize(64);
        assertThat(key).matches("[0-9a-f]{64}");
    }

    @Test
    void generatesUniqueKeysAcrossManyInvocations() {
        int runs = 1_000;
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < runs; i++) {
            keys.add(ApiKeyGenerator.generate());
        }

        assertThat(keys).hasSize(runs);
    }
}
