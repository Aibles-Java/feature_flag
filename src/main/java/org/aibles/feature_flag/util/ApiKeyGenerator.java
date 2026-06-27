package org.aibles.feature_flag.util;

import java.security.SecureRandom;
import java.util.HexFormat;

public final class ApiKeyGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiKeyGenerator() {}

    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes); // 64-char hex
    }
}
