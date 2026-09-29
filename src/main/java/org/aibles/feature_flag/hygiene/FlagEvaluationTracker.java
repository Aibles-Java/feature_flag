package org.aibles.feature_flag.hygiene;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.aibles.feature_flag.config.HygieneProperties;
import org.aibles.feature_flag.repository.FlagEnvironmentStateRepository;
import org.springframework.stereotype.Component;

/**
 * Records that flags were evaluated, cheaply enough to sit on the SDK read path (issue #37).
 *
 * <p>Writing on every read would put an UPDATE in front of every SDK evaluation, so there are two
 * layers of throttling — the same shape {@code ApiKeyAuthenticationFilter} uses for {@code
 * environments.last_used_at}:
 *
 * <ol>
 *   <li>An in-memory Caffeine set of recently-stamped keys, expiring after the throttle window.
 *       This skips the database round-trip entirely for the common case, so a hot environment costs
 *       at most one UPDATE per window no matter the request rate.
 *   <li>A {@code threshold} guard inside the UPDATE itself, which makes the write race-safe across
 *       instances and idempotent — two nodes stamping concurrently cannot double-write, and an
 *       instance that just restarted (empty cache) still cannot write more often than the window.
 * </ol>
 *
 * <p>Fail-safe: any error is logged and swallowed. Usage tracking is bookkeeping, and must never
 * turn an SDK evaluation into a 500.
 *
 * <p><strong>Interaction with the evaluation cache (issue #30).</strong> This tracker must be
 * called from the evaluation path <em>outside</em> any cache-load function. If the call sat inside
 * a cache-miss loader, a cached hit would skip it, and flags being evaluated thousands of times a
 * minute would be reported as stale — the hygiene report would be confidently wrong in the exact
 * case it matters. See {@code decisions/0023}.
 */
@Component
@Slf4j
public class FlagEvaluationTracker {

  /**
   * Marker value — only key presence matters. Caffeine has no {@code Set} type, so this is a cache
   * used as an expiring set.
   */
  private static final Boolean STAMPED = Boolean.TRUE;

  private final FlagEnvironmentStateRepository repository;
  private final HygieneProperties properties;
  private final Cache<String, Boolean> recentlyStamped;

  public FlagEvaluationTracker(
      FlagEnvironmentStateRepository repository, HygieneProperties properties) {
    this.repository = repository;
    this.properties = properties;
    this.recentlyStamped =
        Caffeine.newBuilder()
            .expireAfterWrite(properties.evaluationTouchThrottle())
            // Bounded so a large number of environments/flags cannot grow this without limit.
            // Eviction is harmless: it only costs one extra (guarded, no-op) UPDATE.
            .maximumSize(10_000)
            .build();
  }

  /** Records an evaluation of every active flag in an environment ({@code GET /sdk/flags}). */
  public void recordEnvironmentEvaluation(UUID environmentId) {
    if (environmentId == null) {
      return;
    }
    stamp(
        "env:" + environmentId,
        (now, threshold) ->
            repository.touchLastEvaluatedAtForEnvironment(environmentId, now, threshold));
  }

  /** Records an evaluation of a single flag ({@code GET /sdk/flags/{flagKey}}). */
  public void recordFlagEvaluation(UUID flagId, UUID environmentId) {
    if (flagId == null || environmentId == null) {
      return;
    }
    stamp(
        "flag:" + flagId + ":" + environmentId,
        (now, threshold) -> repository.touchLastEvaluatedAt(flagId, environmentId, now, threshold));
  }

  private void stamp(String key, Touch touch) {
    if (recentlyStamped.getIfPresent(key) != null) {
      return; // stamped within the throttle window — no database round-trip at all
    }
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime threshold = now.minus(properties.evaluationTouchThrottle());
    try {
      touch.apply(now, threshold);
      recentlyStamped.put(key, STAMPED);
    } catch (RuntimeException e) {
      // Never fail an evaluation over bookkeeping. Not cached on failure, so the next request
      // retries rather than silently skipping the whole window.
      log.warn("Could not record flag evaluation for {}: {}", key, e.getClass().getSimpleName());
    }
  }

  @FunctionalInterface
  private interface Touch {
    void apply(LocalDateTime now, LocalDateTime threshold);
  }
}
