package org.aibles.feature_flag.security.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import java.time.Duration;
import org.springframework.stereotype.Service;

/**
 * In-memory token-bucket rate limiter (issue #26, v1). Holds one {@link Bucket} per client key per
 * scope, created lazily from {@link RateLimitProperties}.
 *
 * <p>Buckets are stored in a {@link Caffeine} cache with {@code expireAfterAccess}, so idle keys
 * are evicted rather than accumulating forever (an unbounded per-IP map would be a slow memory leak
 * / DoS vector). Eviction is safe: a bucket idle for longer than its refill window has already
 * refilled to full capacity, so dropping it is equivalent to a fresh full bucket — no meaningful
 * rate-limit state is lost. An actively-abusing key keeps hitting the cache and so keeps its
 * (throttled) bucket alive.
 *
 * <p>In-memory means limits are <strong>per application instance</strong>. That is acceptable for
 * v1; a distributed backend (e.g. Bucket4j + Redis) would be a later upgrade.
 */
@Service
public class RateLimitService {

  public enum Scope {
    AUTH,
    SDK
  }

  /** Idle buckets are kept for this multiple of the refill period before eviction. */
  private static final int IDLE_EVICTION_FACTOR = 2;

  private final RateLimitProperties properties;
  private final Cache<String, Bucket> authBuckets;
  private final Cache<String, Bucket> sdkBuckets;

  public RateLimitService(RateLimitProperties properties) {
    this.properties = properties;
    this.authBuckets = buildCache(properties.getAuth());
    this.sdkBuckets = buildCache(properties.getSdk());
  }

  public boolean isEnabled() {
    return properties.isEnabled();
  }

  /**
   * Attempts to consume one token for {@code key} in {@code scope}; the probe reports the verdict.
   */
  public ConsumptionProbe tryConsume(Scope scope, String key) {
    Cache<String, Bucket> buckets = scope == Scope.AUTH ? authBuckets : sdkBuckets;
    RateLimitProperties.Limit limit =
        scope == Scope.AUTH ? properties.getAuth() : properties.getSdk();
    Bucket bucket = buckets.get(key, k -> newBucket(limit));
    return bucket.tryConsumeAndReturnRemaining(1);
  }

  private Cache<String, Bucket> buildCache(RateLimitProperties.Limit limit) {
    Duration idleTtl = limit.getRefillPeriod().multipliedBy(IDLE_EVICTION_FACTOR);
    return Caffeine.newBuilder().expireAfterAccess(idleTtl).build();
  }

  private Bucket newBucket(RateLimitProperties.Limit limit) {
    return Bucket.builder()
        .addLimit(
            b ->
                b.capacity(limit.getCapacity())
                    .refillGreedy(limit.getCapacity(), limit.getRefillPeriod()))
        .build();
  }
}
