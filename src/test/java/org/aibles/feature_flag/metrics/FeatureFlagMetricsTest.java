package org.aibles.feature_flag.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FeatureFlagMetrics}, asserting each business meter is registered under the
 * expected Micrometer name with bounded tags, and that the evaluation wrapper both runs the
 * supplied work and records count + latency.
 */
class FeatureFlagMetricsTest {

  private SimpleMeterRegistry registry;
  private FeatureFlagMetrics metrics;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    metrics = new FeatureFlagMetrics(registry);
  }

  @Test
  void recordEvaluation_runsWorkAndRecordsCountAndTimer() {
    String result = metrics.recordEvaluation("env-1", () -> "computed");

    // The supplier is actually executed and its value returned.
    assertThat(result).isEqualTo("computed");

    // ff_evaluations_total{environment="env-1"} == 1
    assertThat(registry.counter("ff.evaluations", "environment", "env-1").count()).isEqualTo(1.0);

    // The latency timer recorded exactly one evaluation for that environment.
    assertThat(registry.timer("ff.evaluation.duration", "environment", "env-1").count())
        .isEqualTo(1L);
  }

  @Test
  void recordEvaluation_tagsPerEnvironment() {
    metrics.recordEvaluation("env-a", () -> null);
    metrics.recordEvaluation("env-a", () -> null);
    metrics.recordEvaluation("env-b", () -> null);

    assertThat(registry.counter("ff.evaluations", "environment", "env-a").count()).isEqualTo(2.0);
    assertThat(registry.counter("ff.evaluations", "environment", "env-b").count()).isEqualTo(1.0);
  }

  @Test
  void recordFlagChange_incrementsPerBoundedChangeType() {
    metrics.recordFlagChange(FeatureFlagMetrics.FlagChange.CREATED);
    metrics.recordFlagChange(FeatureFlagMetrics.FlagChange.STATE_UPDATED);
    metrics.recordFlagChange(FeatureFlagMetrics.FlagChange.STATE_UPDATED);

    assertThat(registry.counter("ff.flag.changes", "change", "created").count()).isEqualTo(1.0);
    assertThat(registry.counter("ff.flag.changes", "change", "state_updated").count())
        .isEqualTo(2.0);
  }

  @Test
  void recordAuthFailure_tagsChainAndReason() {
    metrics.recordAuthFailure(FeatureFlagMetrics.AuthFailure.SDK_INVALID_KEY);
    metrics.recordAuthFailure(FeatureFlagMetrics.AuthFailure.ADMIN_UNKNOWN_SUBJECT);

    assertThat(
            registry.counter("ff.auth.failures", "chain", "sdk", "reason", "invalid_key").count())
        .isEqualTo(1.0);
    assertThat(
            registry
                .counter("ff.auth.failures", "chain", "admin", "reason", "unknown_subject")
                .count())
        .isEqualTo(1.0);
  }
}
