package org.aibles.feature_flag.util;

import java.nio.charset.StandardCharsets;

public final class RolloutEvaluator {

    private RolloutEvaluator() {}

    /**
     * Deterministic percentage rollout using MurmurHash3.
     * Same identifier + flagKey always produces the same bucket (0-99),
     * so the same user always gets the same result for a given rollout %.
     */
    public static boolean evaluate(String identifier, String flagKey, int rolloutPercent, boolean enabled) {
        if (!enabled) return false;
        if (rolloutPercent >= 100) return true;
        if (rolloutPercent <= 0) return false;
        if (identifier == null || identifier.isBlank()) return true;

        String input = identifier + ":" + flagKey;
        int hash = murmur3_32(input.getBytes(StandardCharsets.UTF_8), 0);
        int bucket = Math.abs(hash) % 100;
        return bucket < rolloutPercent;
    }

    // MurmurHash3 32-bit (Austin Appleby)
    private static int murmur3_32(byte[] data, int seed) {
        final int c1 = 0xcc9e2d51;
        final int c2 = 0x1b873593;
        int h1 = seed;
        int len = data.length;
        int i = 0;

        while (i + 4 <= len) {
            int k1 = (data[i] & 0xff)
                    | ((data[i + 1] & 0xff) << 8)
                    | ((data[i + 2] & 0xff) << 16)
                    | ((data[i + 3] & 0xff) << 24);
            k1 *= c1;
            k1 = Integer.rotateLeft(k1, 15);
            k1 *= c2;
            h1 ^= k1;
            h1 = Integer.rotateLeft(h1, 13);
            h1 = h1 * 5 + 0xe6546b64;
            i += 4;
        }

        int k1 = 0;
        switch (len & 3) {
            case 3: k1 ^= (data[i + 2] & 0xff) << 16;
            case 2: k1 ^= (data[i + 1] & 0xff) << 8;
            case 1: k1 ^= (data[i] & 0xff);
                k1 *= c1;
                k1 = Integer.rotateLeft(k1, 15);
                k1 *= c2;
                h1 ^= k1;
        }

        h1 ^= len;
        h1 ^= h1 >>> 16;
        h1 *= 0x85ebca6b;
        h1 ^= h1 >>> 13;
        h1 *= 0xc2b2ae35;
        h1 ^= h1 >>> 16;
        return h1;
    }
}
