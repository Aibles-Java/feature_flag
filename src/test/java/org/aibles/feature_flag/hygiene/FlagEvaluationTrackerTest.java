package org.aibles.feature_flag.hygiene;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import org.aibles.feature_flag.config.HygieneProperties;
import org.aibles.feature_flag.repository.FlagEnvironmentStateRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Issue #37 AC: "last evaluation timestamps update without noticeable performance degradation
 * through throttling".
 *
 * <p>The claim being tested is specifically that repeat traffic inside the throttle window costs
 * <strong>zero</strong> database round-trips — not merely that the UPDATE is guarded.
 */
@ExtendWith(MockitoExtension.class)
class FlagEvaluationTrackerTest {

  @Mock FlagEnvironmentStateRepository repository;

  private final UUID envId = UUID.randomUUID();
  private final UUID flagId = UUID.randomUUID();

  private FlagEvaluationTracker tracker(Duration throttle) {
    return new FlagEvaluationTracker(
        repository, new HygieneProperties(Duration.ofDays(30), throttle));
  }

  @Test
  @DisplayName("the first evaluation in a window writes once")
  void firstEvaluationWrites() {
    tracker(Duration.ofMinutes(5)).recordEnvironmentEvaluation(envId);

    verify(repository).touchLastEvaluatedAtForEnvironment(eq(envId), any(), any());
  }

  @Test
  @DisplayName("1,000 evaluations inside the window cost exactly one database round-trip")
  void throttlesRepeatEvaluations() {
    FlagEvaluationTracker tracker = tracker(Duration.ofMinutes(5));

    for (int i = 0; i < 1_000; i++) {
      tracker.recordEnvironmentEvaluation(envId);
    }

    verify(repository, times(1)).touchLastEvaluatedAtForEnvironment(eq(envId), any(), any());
  }

  @Test
  @DisplayName("a zero-length window is rejected by config, so throttling cannot be disabled")
  void throttleMustBePositive() {
    HygieneProperties zero = new HygieneProperties(Duration.ofDays(30), Duration.ZERO);

    org.assertj.core.api.Assertions.assertThat(zero.isThrottlePositive()).isFalse();
  }

  @Test
  void throttlesPerEnvironmentIndependently() {
    FlagEvaluationTracker tracker = tracker(Duration.ofMinutes(5));
    UUID otherEnv = UUID.randomUUID();

    tracker.recordEnvironmentEvaluation(envId);
    tracker.recordEnvironmentEvaluation(otherEnv);
    tracker.recordEnvironmentEvaluation(envId);

    verify(repository, times(1)).touchLastEvaluatedAtForEnvironment(eq(envId), any(), any());
    verify(repository, times(1)).touchLastEvaluatedAtForEnvironment(eq(otherEnv), any(), any());
  }

  @Test
  void throttlesPerFlagAndEnvironmentPair() {
    FlagEvaluationTracker tracker = tracker(Duration.ofMinutes(5));
    UUID otherFlag = UUID.randomUUID();

    tracker.recordFlagEvaluation(flagId, envId);
    tracker.recordFlagEvaluation(flagId, envId);
    tracker.recordFlagEvaluation(otherFlag, envId);

    verify(repository, times(1)).touchLastEvaluatedAt(eq(flagId), eq(envId), any(), any());
    verify(repository, times(1)).touchLastEvaluatedAt(eq(otherFlag), eq(envId), any(), any());
  }

  @Test
  @DisplayName("the window expires, so tracking resumes rather than stopping forever")
  void writesAgainAfterTheWindowExpires() throws InterruptedException {
    FlagEvaluationTracker tracker = tracker(Duration.ofMillis(50));

    tracker.recordEnvironmentEvaluation(envId);
    Thread.sleep(150);
    tracker.recordEnvironmentEvaluation(envId);

    verify(repository, times(2)).touchLastEvaluatedAtForEnvironment(eq(envId), any(), any());
  }

  @Test
  @DisplayName("the threshold passed to the repository is now minus the throttle window")
  void passesAThresholdMatchingTheWindow() {
    tracker(Duration.ofMinutes(5)).recordEnvironmentEvaluation(envId);

    ArgumentCaptor<LocalDateTime> now = ArgumentCaptor.forClass(LocalDateTime.class);
    ArgumentCaptor<LocalDateTime> threshold = ArgumentCaptor.forClass(LocalDateTime.class);
    verify(repository)
        .touchLastEvaluatedAtForEnvironment(eq(envId), now.capture(), threshold.capture());

    // ~5 minutes apart; allow a second of slack for clock reads either side.
    long gapSeconds = Duration.between(threshold.getValue(), now.getValue()).toSeconds();
    org.assertj.core.api.Assertions.assertThat(gapSeconds).isBetween(299L, 301L);
  }

  /** Bookkeeping must never turn an SDK evaluation into a 500. */
  @Test
  @DisplayName("a database failure is swallowed, and is not cached as done")
  void swallowsRepositoryFailuresAndRetriesNextTime() {
    FlagEvaluationTracker tracker = tracker(Duration.ofMinutes(5));
    doThrow(new RuntimeException("db down"))
        .when(repository)
        .touchLastEvaluatedAtForEnvironment(eq(envId), any(), any());

    assertThatCode(() -> tracker.recordEnvironmentEvaluation(envId)).doesNotThrowAnyException();
    // Not marked as stamped, so the next request tries again instead of skipping the window.
    tracker.recordEnvironmentEvaluation(envId);

    verify(repository, times(2)).touchLastEvaluatedAtForEnvironment(eq(envId), any(), any());
  }

  @Test
  void ignoresNullIds() {
    FlagEvaluationTracker tracker = tracker(Duration.ofMinutes(5));

    tracker.recordEnvironmentEvaluation(null);
    tracker.recordFlagEvaluation(null, envId);
    tracker.recordFlagEvaluation(flagId, null);

    verifyNoInteractions(repository);
  }

  @Test
  void environmentAndSingleFlagTrackingAreSeparate() {
    FlagEvaluationTracker tracker = tracker(Duration.ofMinutes(5));

    tracker.recordEnvironmentEvaluation(envId);
    tracker.recordFlagEvaluation(flagId, envId);

    verify(repository).touchLastEvaluatedAtForEnvironment(eq(envId), any(), any());
    verify(repository).touchLastEvaluatedAt(eq(flagId), eq(envId), any(), any());
    verify(repository, never()).touchLastEvaluatedAt(eq(envId), eq(envId), any(), any());
  }
}
