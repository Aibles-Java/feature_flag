package org.aibles.feature_flag.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Issue #35 acceptance criteria for {@link RolloutEvaluator}.
 *
 * <p>Everything here is deterministic — identifiers are generated, never random — so a failure is a
 * real regression in the bucketing, not a flake.
 */
class RolloutEvaluatorTest {

  private static final String FLAG_KEY = "beta-feature";
  private static final int SAMPLE = 10_000;

  private static List<String> identifiers(int count) {
    List<String> ids = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      ids.add("user-" + i);
    }
    return ids;
  }

  // --- AC: identical identifier+flagKey always returns the same result -----------------------

  @Test
  @DisplayName("same identifier + flagKey yields the same decision on every call")
  void isDeterministic() {
    for (String id : identifiers(500)) {
      for (int percent : new int[] {1, 25, 50, 75, 99}) {
        boolean first = RolloutEvaluator.evaluate(id, FLAG_KEY, percent, true);
        for (int repeat = 0; repeat < 5; repeat++) {
          assertThat(RolloutEvaluator.evaluate(id, FLAG_KEY, percent, true))
              .as("identifier %s at %d%%", id, percent)
              .isEqualTo(first);
        }
      }
    }
  }

  @Test
  @DisplayName("raising the rollout percentage never removes an already-included identifier")
  void isMonotonicInRolloutPercent() {
    for (String id : identifiers(1_000)) {
      int includedFrom = 101;
      for (int percent = 1; percent <= 100; percent++) {
        boolean on = RolloutEvaluator.evaluate(id, FLAG_KEY, percent, true);
        if (on && percent < includedFrom) {
          includedFrom = percent;
        }
        // Once included at a lower percentage, it must stay included at every higher one.
        assertThat(on).as("identifier %s at %d%%", id, percent).isEqualTo(percent >= includedFrom);
      }
    }
  }

  // --- AC: bucket distribution across 10,000 identifiers is approximately uniform -----------

  @Test
  @DisplayName("10,000 identifiers spread across all 100 buckets within tolerance")
  void bucketDistributionIsApproximatelyUniform() {
    int[] perBucket = new int[100];
    for (String id : identifiers(SAMPLE)) {
      perBucket[RolloutEvaluator.bucketFor(id, FLAG_KEY)]++;
    }

    int expected = SAMPLE / 100; // 100 per bucket
    assertThat(perBucket).as("no bucket may be empty").doesNotContain(0);
    // Chi-square goodness of fit against a uniform expectation. 99 degrees of freedom;
    // the 99.9th-percentile critical value is ~148.2, so a well-behaved hash stays far below.
    double chiSquare = 0;
    for (int count : perBucket) {
      double delta = count - expected;
      chiSquare += (delta * delta) / expected;
    }
    assertThat(chiSquare).as("chi-square vs uniform over 100 buckets").isLessThan(148.2);
  }

  @ParameterizedTest(name = "rollout {0}% includes roughly {0}% of 10,000 identifiers")
  @ValueSource(ints = {10, 25, 50, 75, 90})
  void includedShareTracksRolloutPercent(int percent) {
    long included =
        identifiers(SAMPLE).stream()
            .filter(id -> RolloutEvaluator.evaluate(id, FLAG_KEY, percent, true))
            .count();

    double share = included * 100.0 / SAMPLE;
    // Absolute tolerance of 2 percentage points on a 10,000 sample.
    assertThat(share).isCloseTo(percent, org.assertj.core.data.Offset.offset(2.0));
  }

  @Test
  @DisplayName("flags bucket independently — the same identifier is not in the same bucket for all")
  void bucketsAreIndependentPerFlagKey() {
    String id = "user-42";
    Set<Integer> buckets = new LinkedHashSet<>();
    for (int i = 0; i < 50; i++) {
      buckets.add(RolloutEvaluator.bucketFor(id, "flag-" + i));
    }
    // If flagKey were ignored, every flag would share one bucket.
    assertThat(buckets)
        .as("distinct buckets for one identifier across 50 flags")
        .hasSizeGreaterThan(20);
  }

  // --- Bucket range, including the pathological hash ----------------------------------------

  @Test
  @DisplayName("bucketFor stays within [0,100) across 10,000 identifiers")
  void bucketForIsAlwaysInRange() {
    for (String id : identifiers(SAMPLE)) {
      assertThat(RolloutEvaluator.bucketFor(id, FLAG_KEY)).isBetween(0, 99);
    }
  }

  @ParameterizedTest(name = "toBucket({0}) is in [0,100)")
  @ValueSource(ints = {Integer.MIN_VALUE, Integer.MIN_VALUE + 1, -1, 0, 1, Integer.MAX_VALUE})
  void toBucketIsNeverNegative(int hash) {
    // Regression guard: Math.abs(Integer.MIN_VALUE) is still Integer.MIN_VALUE, and
    // Integer.MIN_VALUE % 100 == -48 in Java. A negative bucket compares below every
    // rollout percentage, so that one identifier would be permanently included even at 1%.
    assertThat(RolloutEvaluator.toBucket(hash)).isBetween(0, 99);
  }

  // --- Documented edge cases ----------------------------------------------------------------

  @Test
  @DisplayName("a disabled flag stays off regardless of rollout percentage")
  void disabledFlagIsAlwaysOff() {
    assertThat(RolloutEvaluator.evaluate("user-1", FLAG_KEY, 100, false)).isFalse();
    assertThat(RolloutEvaluator.evaluate("user-1", FLAG_KEY, 50, false)).isFalse();
    assertThat(RolloutEvaluator.evaluate(null, FLAG_KEY, 100, false)).isFalse();
  }

  @ParameterizedTest(name = "rolloutPercent {0} is fully on")
  @ValueSource(ints = {100, 101, Integer.MAX_VALUE})
  void fullRolloutIsAlwaysOn(int percent) {
    assertThat(RolloutEvaluator.evaluate("user-1", FLAG_KEY, percent, true)).isTrue();
  }

  @ParameterizedTest(name = "rolloutPercent {0} is fully off")
  @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
  void zeroRolloutIsAlwaysOff(int percent) {
    assertThat(RolloutEvaluator.evaluate("user-1", FLAG_KEY, percent, true)).isFalse();
  }

  @Test
  @DisplayName("a missing identifier fails OPEN on a partial rollout (documented contract)")
  void missingIdentifierFailsOpen() {
    // Deliberate: an anonymous caller sees the flag's plain enabled state, keeping the SDK
    // contract backward compatible. Consequence: a rollout percentage is NOT access control.
    // See ADR-0004.
    assertThat(RolloutEvaluator.evaluate(null, FLAG_KEY, 1, true)).isTrue();
    assertThat(RolloutEvaluator.evaluate("", FLAG_KEY, 1, true)).isTrue();
    assertThat(RolloutEvaluator.evaluate("   ", FLAG_KEY, 1, true)).isTrue();
  }
}
