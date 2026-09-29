package org.aibles.feature_flag.sdk;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.aibles.feature_flag.sdk.exception.InvalidApiKeyException;
import org.aibles.feature_flag.sdk.internal.CacheEntry;
import org.aibles.feature_flag.sdk.internal.DiagnosticsCollector;
import org.aibles.feature_flag.sdk.internal.DiagnosticsCollector.DiagnosticsSnapshot;
import org.aibles.feature_flag.sdk.internal.FlagCache;
import org.aibles.feature_flag.sdk.internal.RetryPolicy;
import org.aibles.feature_flag.sdk.internal.SdkConfig;
import org.aibles.feature_flag.sdk.internal.TtlFlagCache;
import org.aibles.feature_flag.sdk.internal.TypeCoercionEngine;
import org.aibles.feature_flag.sdk.internal.http.JdkFlagHttpClient;
import org.slf4j.LoggerFactory;

/**
 * Public API façade for the Feature Flag SDK.
 *
 * <p>Instances are thread-safe and should be shared as a singleton in the consuming application.
 * Create via {@link FlagClientBuilder}.
 *
 * <h2>Cache / fetch strategy</h2>
 *
 * <ol>
 *   <li>Rollout flags ({@code rolloutPercent > 0 && < 100}) always bypass cache and go to {@code
 *       fetchOne} (ADR-SDK-004) so every caller is bucketed individually.
 *   <li>Non-rollout flags are served from cache when fresh.
 *   <li>On a cache miss, {@code fetchAll} pre-warms the cache for the entire environment then
 *       returns the requested key from the freshly populated cache.
 *   <li>On server error (non-401 failure), the serve-stale fallback is tried; if that also misses,
 *       the caller default is returned.
 *   <li>On 401, {@link InvalidApiKeyException} is thrown — no retry, no serve-stale.
 * </ol>
 *
 * <h2>Confidential rollout fail-closed (ADR-SDK-004 E2)</h2>
 *
 * When a flag key matches the CONFIDENTIAL pattern (card|payment|fraud|kyc|aml|security|pci) AND is
 * a rollout flag AND no identifier is supplied, the caller default is returned instead of the
 * server's fully-on value. This is a SDK-side policy; the server itself fail-opens when identifier
 * is omitted (documented in EvaluationController).
 */
public final class FlagClient implements AutoCloseable {

  private static final org.slf4j.Logger log = LoggerFactory.getLogger(FlagClient.class);

  /**
   * Flag keys matching this pattern are treated as CONFIDENTIAL. When such a key is on a rollout
   * and no identifier is supplied, the SDK returns the caller default (fail-closed).
   */
  private static final Pattern CONFIDENTIAL_KEY_PATTERN =
      Pattern.compile("(?i).*(card|payment|fraud|kyc|aml|security|pci).*");

  private final SdkConfig config;
  private final JdkFlagHttpClient httpClient;
  private final FlagCache cache;
  private final DiagnosticsCollector diagnostics;
  private final RetryPolicy retryPolicy;

  FlagClient(SdkConfig config) {
    this.config = config;
    this.httpClient = new JdkFlagHttpClient(config);
    this.cache = new TtlFlagCache(config.getCacheTtlSeconds(), config.getMaxStaleSeconds());
    this.diagnostics = new DiagnosticsCollector();
    this.retryPolicy = RetryPolicy.DEFAULT;
  }

  /** Package-private constructor for testing: allows injection of collaborators. */
  FlagClient(
      SdkConfig config,
      JdkFlagHttpClient httpClient,
      FlagCache cache,
      DiagnosticsCollector diagnostics,
      RetryPolicy retryPolicy) {
    this.config = config;
    this.httpClient = httpClient;
    this.cache = cache;
    this.diagnostics = diagnostics;
    this.retryPolicy = retryPolicy;
  }

  SdkConfig getConfig() {
    return config;
  }

  // ---------------------------------------------------------------------------
  // getBooleanValue
  // ---------------------------------------------------------------------------

  /**
   * Evaluates a boolean flag.
   *
   * @param flagKey the flag key (immutable slug)
   * @param defaultValue returned when the flag is off, not found, or on server error
   */
  public boolean getBooleanValue(String flagKey, boolean defaultValue) {
    return getBooleanValue(flagKey, null, defaultValue);
  }

  /**
   * Evaluates a boolean flag with an identifier for rollout bucketing.
   *
   * @param flagKey the flag key
   * @param identifier caller identity for rollout bucketing; may be null
   * @param defaultValue returned when the flag is off, not found, or on server error
   */
  public boolean getBooleanValue(String flagKey, String identifier, boolean defaultValue) {
    CacheEntry entry = resolveEntry(flagKey, identifier, defaultValue);
    if (entry == null) return defaultValue;
    return TypeCoercionEngine.coerceBoolean(
        flagKey, entry.isEnabled(), entry.getValue(), entry.getValueType(), defaultValue);
  }

  // ---------------------------------------------------------------------------
  // getStringValue
  // ---------------------------------------------------------------------------

  public String getStringValue(String flagKey, String defaultValue) {
    return getStringValue(flagKey, null, defaultValue);
  }

  public String getStringValue(String flagKey, String identifier, String defaultValue) {
    CacheEntry entry = resolveEntry(flagKey, identifier, defaultValue);
    if (entry == null) return defaultValue;
    return TypeCoercionEngine.coerceString(
        flagKey, entry.isEnabled(), entry.getValue(), entry.getValueType(), defaultValue);
  }

  // ---------------------------------------------------------------------------
  // getIntValue
  // ---------------------------------------------------------------------------

  public int getIntValue(String flagKey, int defaultValue) {
    return getIntValue(flagKey, null, defaultValue);
  }

  public int getIntValue(String flagKey, String identifier, int defaultValue) {
    CacheEntry entry = resolveEntry(flagKey, identifier, defaultValue);
    if (entry == null) return defaultValue;
    return TypeCoercionEngine.coerceInteger(
        flagKey, entry.isEnabled(), entry.getValue(), entry.getValueType(), defaultValue);
  }

  // ---------------------------------------------------------------------------
  // getJsonValue
  // ---------------------------------------------------------------------------

  public <T> T getJsonValue(String flagKey, Class<T> targetClass, T defaultValue) {
    return getJsonValue(flagKey, null, targetClass, defaultValue);
  }

  public <T> T getJsonValue(
      String flagKey, String identifier, Class<T> targetClass, T defaultValue) {
    CacheEntry entry = resolveEntry(flagKey, identifier, defaultValue);
    if (entry == null) return defaultValue;
    return TypeCoercionEngine.coerceJson(
        flagKey,
        entry.isEnabled(),
        entry.getValue(),
        entry.getValueType(),
        targetClass,
        defaultValue);
  }

  // ---------------------------------------------------------------------------
  // Diagnostics
  // ---------------------------------------------------------------------------

  /**
   * Returns a point-in-time snapshot of SDK diagnostics counters, including the current cache size
   * (SF-2 / D4 heap-monitoring mitigation).
   */
  public DiagnosticsSnapshot diagnostics() {
    return diagnostics.snapshot(cache.size());
  }

  // ---------------------------------------------------------------------------
  // AutoCloseable
  // ---------------------------------------------------------------------------

  @Override
  public void close() {
    cache.close();
  }

  // ---------------------------------------------------------------------------
  // Internal entry resolution
  // ---------------------------------------------------------------------------

  /**
   * Core resolution logic: cache-first, fetch on miss, serve-stale on error, caller default as last
   * resort.
   *
   * @param sentinel not used for logic; present so generic overloads compile cleanly
   */
  private <D> CacheEntry resolveEntry(String flagKey, String identifier, D defaultValue) {
    // Fail-closed: confidential rollout flag without identifier → return default immediately
    // (ADR-SDK-004 E2). We check the cache first to see if the flag is a rollout flag.
    Optional<CacheEntry> cached = cache.get(flagKey);
    if (cached.isPresent()) {
      CacheEntry ce = cached.get();
      if (ce.isRollout()) {
        if (isConfidentialFailClosed(flagKey, identifier)) {
          return null;
        }
        // Rollout flags bypass cache — always fetch fresh.
        return fetchOneWithFallback(flagKey, identifier, ce);
      }
      diagnostics.recordCacheHit();
      return ce;
    }

    // Cache miss: attempt bulk pre-warm via fetchAll, then serve from newly populated cache.
    diagnostics.recordCacheMiss();
    Set<CacheEntry> fetched = fetchAllWithRetry(identifier);
    if (fetched != null) {
      Optional<CacheEntry> fresh = cache.get(flagKey);
      if (fresh.isPresent()) {
        CacheEntry ce = fresh.get();
        if (ce.isRollout()) {
          if (isConfidentialFailClosed(flagKey, identifier)) {
            return null;
          }
          return fetchOneWithFallback(flagKey, identifier, ce);
        }
        return ce;
      }
      // Key not in bulk response → try single fetch.
    }

    // Either fetchAll failed or key not present. Try fetchOne directly.
    CacheEntry fallbackEntry = fetchOneWithFallback(flagKey, identifier, null);
    // HF-1: apply fail-closed check on any path that reaches fetchOne.
    // If the fetched entry is a rollout AND confidential AND no identifier → return null (default).
    if (fallbackEntry != null
        && fallbackEntry.isRollout()
        && isConfidentialFailClosed(flagKey, identifier)) {
      return null;
    }
    return fallbackEntry;
  }

  /**
   * Fetches all flags with retry, stores them in cache. Returns a non-null sentinel on success (the
   * actual entries are in the cache), null if all retries exhausted.
   */
  private Set<CacheEntry> fetchAllWithRetry(String identifier) {
    int attempt = 0;
    int lastStatus = -1;
    while (true) {
      try {
        List<CacheEntry> entries = httpClient.fetchAll(identifier);
        cache.putAll(entries);
        return Set.of(); // non-null sentinel
      } catch (InvalidApiKeyException e) {
        diagnostics.recordInvalidKeyEvent();
        throw e;
      } catch (IOException e) {
        diagnostics.recordServerError();
        lastStatus = -1;
        if (!retryPolicy.shouldRetry(lastStatus, attempt)) {
          log.debug("SDK fetchAll exhausted retries after attempt {}", attempt);
          return null;
        }
        attempt++;
        sleepUninterruptibly(retryPolicy.nextDelayMs(attempt - 1));
      }
    }
  }

  /**
   * Fetches a single flag with retry. Falls back to {@code staleEntry} (if non-null) on exhausted
   * retries. Returns null if no stale entry is available.
   */
  private CacheEntry fetchOneWithFallback(
      String flagKey, String identifier, CacheEntry staleEntry) {
    int attempt = 0;
    while (true) {
      try {
        CacheEntry entry = httpClient.fetchOne(flagKey, identifier);
        if (entry != null) {
          cache.put(flagKey, entry);
          return entry;
        }
        // Non-200 non-401 → serve stale or default.
        return serveStale(flagKey, staleEntry);
      } catch (InvalidApiKeyException e) {
        diagnostics.recordInvalidKeyEvent();
        throw e;
      } catch (IOException e) {
        diagnostics.recordServerError();
        int statusCode = -1; // network / IO error
        if (!retryPolicy.shouldRetry(statusCode, attempt)) {
          log.debug("SDK fetchOne exhausted retries for flag [key] attempt {}", attempt);
          return serveStale(flagKey, staleEntry);
        }
        attempt++;
        sleepUninterruptibly(retryPolicy.nextDelayMs(attempt - 1));
      }
    }
  }

  /**
   * Returns a stale entry from cache (if present and within maxStale bound), or the provided
   * pre-fetched stale entry, or null.
   */
  private CacheEntry serveStale(String flagKey, CacheEntry preloadedStale) {
    Optional<CacheEntry> fromCache = cache.getStale(flagKey);
    if (fromCache.isPresent()) {
      return fromCache.get();
    }
    return preloadedStale;
  }

  /**
   * ADR-SDK-004 E2: returns true when the flag should fail-closed. A flag fails closed when its key
   * matches the CONFIDENTIAL pattern AND it is a rollout flag AND no identifier is provided.
   *
   * <p>Note: we receive the entry to check rollout status; if null we conservatively assume fail-
   * closed for CONFIDENTIAL keys.
   */
  private static boolean isConfidentialFailClosed(String flagKey, String identifier) {
    if (identifier != null && !identifier.isBlank()) {
      return false; // identifier present — normal rollout evaluation
    }
    return CONFIDENTIAL_KEY_PATTERN.matcher(flagKey).matches();
  }

  private static void sleepUninterruptibly(long millis) {
    if (millis <= 0) return;
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
