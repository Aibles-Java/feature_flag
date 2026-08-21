package org.aibles.feature_flag.util;

import java.nio.charset.StandardCharsets;

/**
 * Deterministic identifier-based percentage rollout.
 *
 * <p>An identifier is bucketed into one of 100 buckets by hashing {@code identifier:flagKey} with
 * MurmurHash3. The same identifier therefore always lands in the same bucket for a given flag, so a
 * caller's result is stable across requests, processes and restarts — and raising the rollout
 * percentage only ever <em>adds</em> identifiers, never flips an already-included one back off.
 *
 * <p>Hashing {@code identifier:flagKey} rather than the identifier alone keeps flags independent:
 * the same user is not systematically in the first bucket of every flag.
 *
 * <h2>Contract when no identifier is supplied</h2>
 *
 * <p>The SDK's {@code identifier} query parameter is optional, so a caller may ask for a flag that
 * is on a partial rollout without supplying one. Such a request cannot be bucketed
 * deterministically, so this evaluator <strong>fails open</strong>: the flag is treated as fully
 * on.
 *
 * <p>This is deliberate — an anonymous caller sees the flag's plain {@code enabled} state, exactly
 * as it behaved before rollout percentages existed, which keeps the SDK contract backward
 * compatible. The trade-off is that a partial rollout is <em>not</em> an access-control mechanism:
 * any caller can obtain the "on" branch by omitting the identifier. Never gate a security-sensitive
 * behaviour on a rollout percentage. See {@code docs/adr/ADR-0004-percentage-rollout-contract.md}.
 */
public final class RolloutEvaluator {

  /** Number of buckets. A rollout percentage maps 1:1 onto bucket indices. */
  private static final int BUCKETS = 100;

  private RolloutEvaluator() {}

  /**
   * Resolves the effective state of a flag for one caller.
   *
   * @param identifier stable caller identity (user id, device id, …); may be {@code null}/blank, in
   *     which case the rollout is not applied — see the class-level contract
   * @param flagKey the flag's immutable key, mixed into the hash so flags bucket independently
   * @param rolloutPercent 0–100; {@code >= 100} is fully on, {@code <= 0} fully off
   * @param enabled the flag's configured state for the environment; when {@code false} the rollout
   *     is irrelevant and the result is {@code false}
   * @return whether the flag is on for this caller
   */
  public static boolean evaluate(
      String identifier, String flagKey, int rolloutPercent, boolean enabled) {
    if (!enabled) return false;
    if (rolloutPercent >= BUCKETS) return true;
    if (rolloutPercent <= 0) return false;
    // No identifier => no deterministic bucket. Fail open (see class Javadoc).
    if (identifier == null || identifier.isBlank()) return true;

    return bucketFor(identifier, flagKey) < rolloutPercent;
  }

  /**
   * The stable bucket in {@code [0, 100)} for an identifier/flag pair.
   *
   * <p>Package-private so tests can assert determinism and bucket distribution directly rather than
   * inferring them from {@link #evaluate} decisions.
   */
  static int bucketFor(String identifier, String flagKey) {
    String input = identifier + ":" + flagKey;
    return toBucket(murmur3_32(input.getBytes(StandardCharsets.UTF_8), 0));
  }

  /**
   * Maps a signed 32-bit hash onto {@code [0, 100)}.
   *
   * <p>Clears the sign bit rather than calling {@code Math.abs}: {@code
   * Math.abs(Integer.MIN_VALUE)} returns {@code Integer.MIN_VALUE} — still negative — and {@code
   * Integer.MIN_VALUE % 100} is {@code -48} in Java, because the remainder takes the sign of the
   * dividend. A negative bucket compares below every rollout percentage, so the one identifier
   * hashing to {@code MIN_VALUE} would be permanently included even at a 1% rollout.
   */
  static int toBucket(int hash) {
    return (hash & Integer.MAX_VALUE) % BUCKETS;
  }

  // MurmurHash3 32-bit (Austin Appleby)
  private static int murmur3_32(byte[] data, int seed) {
    final int c1 = 0xcc9e2d51;
    final int c2 = 0x1b873593;
    int h1 = seed;
    int len = data.length;
    int i = 0;

    while (i + 4 <= len) {
      int k1 =
          (data[i] & 0xff)
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
      case 3:
        k1 ^= (data[i + 2] & 0xff) << 16;
      case 2:
        k1 ^= (data[i + 1] & 0xff) << 8;
      case 1:
        k1 ^= (data[i] & 0xff);
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
