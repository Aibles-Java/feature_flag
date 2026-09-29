package org.aibles.feature_flag.sdk;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import org.aibles.feature_flag.sdk.exception.FlagTypeMismatchException;
import org.aibles.feature_flag.sdk.exception.InvalidApiKeyException;
import org.aibles.feature_flag.sdk.internal.CacheEntry;
import org.aibles.feature_flag.sdk.internal.DiagnosticsCollector;
import org.aibles.feature_flag.sdk.internal.DiagnosticsCollector.DiagnosticsSnapshot;
import org.aibles.feature_flag.sdk.internal.FlagCache;
import org.aibles.feature_flag.sdk.internal.RetryPolicy;
import org.aibles.feature_flag.sdk.internal.SdkConfig;
import org.aibles.feature_flag.sdk.internal.TtlFlagCache;
import org.aibles.feature_flag.sdk.internal.http.JdkFlagHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@link FlagClient}: cache hit/miss, serve-stale, rollout bypass,
 * confidential fail-closed, type coercion, diagnostics, and 401 propagation.
 */
class FlagClientTest {

  private static final String SYNTHETIC_KEY =
      "syn-client-test-key-0000000000000000000000000000000000000000000000000000000";

  private HttpServer httpServer;
  private int serverPort;

  @BeforeEach
  void startServer() throws IOException {
    httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    serverPort = httpServer.getAddress().getPort();
    httpServer.start();
  }

  @AfterEach
  void stopServer() {
    if (httpServer != null) httpServer.stop(0);
  }

  // ---------------------------------------------------------------------------
  // Cache hit (flag already in cache)
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("getBooleanValue returns cached value on cache hit")
  void getBooleanValueCacheHit() throws Exception {
    // Pre-populate cache with a fresh entry — no HTTP request should be made
    TtlFlagCache cache = new TtlFlagCache(60, 0);
    cache.put(
        "dark-launch",
        new CacheEntry("dark-launch", true, "true", FlagValueType.BOOLEAN, 0, System.nanoTime()));

    AtomicInteger httpHits = new AtomicInteger(0);
    httpServer.createContext(
        "/api/v1/sdk/flags",
        exchange -> {
          httpHits.incrementAndGet();
          exchange.sendResponseHeaders(500, -1);
          exchange.getResponseBody().close();
        });

    FlagClient client = buildClient(cache, RetryPolicy.DEFAULT);
    try {
      boolean result = client.getBooleanValue("dark-launch", false);
      assertTrue(result, "Should return cached true value");
      assertEquals(0, httpHits.get(), "No HTTP call should be made on cache hit");
    } finally {
      client.close();
      cache.close();
    }
  }

  // ---------------------------------------------------------------------------
  // Cache miss → fetchAll pre-warm
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("getBooleanValue pre-warms cache via fetchAll on cache miss")
  void getBooleanValueCacheMissPrewarmsViaFetchAll() throws Exception {
    String responseBody =
        "[{\"flagKey\":\"new-flag\",\"enabled\":true,\"value\":\"true\","
            + "\"valueType\":\"BOOLEAN\",\"rolloutPercent\":0}]";

    httpServer.createContext(
        "/api/v1/sdk/flags",
        exchange -> {
          byte[] bytes = responseBody.getBytes();
          exchange.sendResponseHeaders(200, bytes.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
          }
        });

    FlagClient client = buildClientWithFreshCache();
    try {
      boolean result = client.getBooleanValue("new-flag", false);
      assertTrue(result);
    } finally {
      client.close();
    }
  }

  // ---------------------------------------------------------------------------
  // Serve stale on server error
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("getBooleanValue serves stale entry when server is unreachable and stale present")
  void getBooleanValueServesStaleOnServerError() throws Exception {
    // Put an expired entry in the cache (stale)
    long fiveMinutesAgoNanos = System.nanoTime() - 5L * 60 * 1_000_000_000L;
    TtlFlagCache cache = new TtlFlagCache(60, 0); // ttl=60s, maxStale=0 (unlimited stale)
    cache.put(
        "stale-flag",
        new CacheEntry("stale-flag", true, "true", FlagValueType.BOOLEAN, 0, fiveMinutesAgoNanos));

    // Server returns 500 on fetchAll
    httpServer.createContext(
        "/api/v1/sdk/flags",
        exchange -> {
          exchange.sendResponseHeaders(500, -1);
          exchange.getResponseBody().close();
        });
    // Server returns 500 on fetchOne too
    httpServer.createContext(
        "/api/v1/sdk/flags/stale-flag",
        exchange -> {
          exchange.sendResponseHeaders(500, -1);
          exchange.getResponseBody().close();
        });

    // No retries so test is fast
    FlagClient client = buildClient(cache, new RetryPolicy(0, 1.0, 0, 0.0));
    try {
      boolean result = client.getBooleanValue("stale-flag", false);
      assertTrue(result, "Must serve stale cached value when server is unavailable");
    } finally {
      client.close();
      cache.close();
    }
  }

  // ---------------------------------------------------------------------------
  // Rollout flag always bypasses cache
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("rollout flag bypasses cache and calls fetchOne each time")
  void rolloutFlagBypassesCache() throws Exception {
    // Pre-populate cache with a rollout=50 entry
    TtlFlagCache cache = new TtlFlagCache(60, 0);
    cache.put(
        "rollout-flag",
        new CacheEntry("rollout-flag", true, "true", FlagValueType.BOOLEAN, 50, System.nanoTime()));

    AtomicInteger fetchOneHits = new AtomicInteger(0);
    String singleFlagResponse =
        "{\"flagKey\":\"rollout-flag\",\"enabled\":true,\"value\":\"true\","
            + "\"valueType\":\"BOOLEAN\",\"rolloutPercent\":50}";
    httpServer.createContext(
        "/api/v1/sdk/flags/rollout-flag",
        exchange -> {
          fetchOneHits.incrementAndGet();
          byte[] bytes = singleFlagResponse.getBytes();
          exchange.sendResponseHeaders(200, bytes.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
          }
        });

    FlagClient client = buildClient(cache, new RetryPolicy(0, 1.0, 0, 0.0));
    try {
      client.getBooleanValue("rollout-flag", "user-1", false);
      client.getBooleanValue("rollout-flag", "user-1", false);
      assertTrue(
          fetchOneHits.get() >= 2, "Rollout flag must bypass cache and call fetchOne each time");
    } finally {
      client.close();
      cache.close();
    }
  }

  // ---------------------------------------------------------------------------
  // 401 → InvalidApiKeyException (no retry, no serve-stale)
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("getBooleanValue throws InvalidApiKeyException on 401 — no retry, no serve-stale")
  void getBooleanValueThrowsOn401() throws Exception {
    httpServer.createContext(
        "/api/v1/sdk/flags",
        exchange -> {
          exchange.sendResponseHeaders(401, -1);
          exchange.getResponseBody().close();
        });

    FlagClient client = buildClientWithFreshCache();
    try {
      assertThrows(
          InvalidApiKeyException.class,
          () -> client.getBooleanValue("some-flag", false),
          "Must propagate InvalidApiKeyException on 401");
    } finally {
      client.close();
    }
  }

  // ---------------------------------------------------------------------------
  // Type coercion
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("getStringValue returns string flag value")
  void getStringValueHappyPath() throws Exception {
    String responseBody =
        "[{\"flagKey\":\"plan-name\",\"enabled\":true,\"value\":\"premium\","
            + "\"valueType\":\"STRING\",\"rolloutPercent\":0}]";
    httpServer.createContext(
        "/api/v1/sdk/flags",
        exchange -> {
          byte[] bytes = responseBody.getBytes();
          exchange.sendResponseHeaders(200, bytes.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
          }
        });

    FlagClient client = buildClientWithFreshCache();
    try {
      String result = client.getStringValue("plan-name", "free");
      assertEquals("premium", result);
    } finally {
      client.close();
    }
  }

  @Test
  @DisplayName("getIntValue returns integer flag value")
  void getIntValueHappyPath() throws Exception {
    String responseBody =
        "[{\"flagKey\":\"max-retries\",\"enabled\":true,\"value\":\"5\","
            + "\"valueType\":\"INTEGER\",\"rolloutPercent\":0}]";
    httpServer.createContext(
        "/api/v1/sdk/flags",
        exchange -> {
          byte[] bytes = responseBody.getBytes();
          exchange.sendResponseHeaders(200, bytes.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
          }
        });

    FlagClient client = buildClientWithFreshCache();
    try {
      int result = client.getIntValue("max-retries", 3);
      assertEquals(5, result);
    } finally {
      client.close();
    }
  }

  @Test
  @DisplayName("getStringValue throws FlagTypeMismatchException when type=INTEGER and enabled=true")
  void getStringValueThrowsOnTypeMismatch() throws Exception {
    TtlFlagCache cache = new TtlFlagCache(60, 0);
    cache.put(
        "wrong-type",
        new CacheEntry("wrong-type", true, "42", FlagValueType.INTEGER, 0, System.nanoTime()));

    FlagClient client = buildClient(cache, RetryPolicy.DEFAULT);
    try {
      assertThrows(
          FlagTypeMismatchException.class, () -> client.getStringValue("wrong-type", "default"));
    } finally {
      client.close();
      cache.close();
    }
  }

  @Test
  @DisplayName("getStringValue returns default silently when flag is disabled (OQ-07)")
  void getStringValueReturnsDefaultWhenDisabled() throws Exception {
    TtlFlagCache cache = new TtlFlagCache(60, 0);
    // Disabled flag with wrong type — no exception
    cache.put(
        "off-flag",
        new CacheEntry("off-flag", false, null, FlagValueType.INTEGER, 0, System.nanoTime()));

    FlagClient client = buildClient(cache, RetryPolicy.DEFAULT);
    try {
      String result = client.getStringValue("off-flag", "my-default");
      assertEquals("my-default", result, "Disabled flag must return default silently");
    } finally {
      client.close();
      cache.close();
    }
  }

  // ---------------------------------------------------------------------------
  // Diagnostics counters
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("diagnostics cacheHits incremented on cache hit")
  void diagnosticsCacheHitIncremented() throws Exception {
    TtlFlagCache cache = new TtlFlagCache(60, 0);
    cache.put(
        "diag-flag",
        new CacheEntry("diag-flag", true, "true", FlagValueType.BOOLEAN, 0, System.nanoTime()));

    FlagClient client = buildClient(cache, RetryPolicy.DEFAULT);
    try {
      client.getBooleanValue("diag-flag", false);
      DiagnosticsSnapshot snap = client.diagnostics();
      assertEquals(1, snap.cacheHits());
    } finally {
      client.close();
      cache.close();
    }
  }

  @Test
  @DisplayName("diagnostics cacheMisses incremented on cache miss")
  void diagnosticsCacheMissIncremented() throws Exception {
    String responseBody =
        "[{\"flagKey\":\"miss-flag\",\"enabled\":false,\"value\":null,"
            + "\"valueType\":\"BOOLEAN\",\"rolloutPercent\":0}]";
    httpServer.createContext(
        "/api/v1/sdk/flags",
        exchange -> {
          byte[] bytes = responseBody.getBytes();
          exchange.sendResponseHeaders(200, bytes.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
          }
        });

    FlagClient client = buildClientWithFreshCache();
    try {
      client.getBooleanValue("miss-flag", true);
      DiagnosticsSnapshot snap = client.diagnostics();
      assertEquals(1, snap.cacheMisses());
    } finally {
      client.close();
    }
  }

  @Test
  @DisplayName("diagnostics invalidKeyEvents incremented on 401")
  void diagnosticsInvalidKeyEventIncremented() throws Exception {
    httpServer.createContext(
        "/api/v1/sdk/flags",
        exchange -> {
          exchange.sendResponseHeaders(401, -1);
          exchange.getResponseBody().close();
        });

    FlagClient client = buildClientWithFreshCache();
    try {
      assertThrows(InvalidApiKeyException.class, () -> client.getBooleanValue("any-flag", false));
      DiagnosticsSnapshot snap = client.diagnostics();
      assertEquals(1, snap.invalidKeyEvents());
    } finally {
      client.close();
    }
  }

  // ---------------------------------------------------------------------------
  // Confidential rollout fail-closed (ADR-SDK-004 E2)
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("ADR-SDK-004 E2: confidential rollout flag with no identifier returns default")
  void confidentialRolloutFlagNoIdentifierReturnsDefault() throws Exception {
    // Pre-seed with a rollout=50 flag for a CONFIDENTIAL key (contains 'payment')
    TtlFlagCache cache = new TtlFlagCache(60, 0);
    cache.put(
        "payment-new-flow",
        new CacheEntry(
            "payment-new-flow", true, "true", FlagValueType.BOOLEAN, 50, System.nanoTime()));

    // Make sure fetchOne also returns true so we can confirm the default was used
    httpServer.createContext(
        "/api/v1/sdk/flags/payment-new-flow",
        exchange -> {
          String body =
              "{\"flagKey\":\"payment-new-flow\",\"enabled\":true,\"value\":\"true\","
                  + "\"valueType\":\"BOOLEAN\",\"rolloutPercent\":50}";
          byte[] bytes = body.getBytes();
          exchange.sendResponseHeaders(200, bytes.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
          }
        });

    FlagClient client = buildClient(cache, new RetryPolicy(0, 1.0, 0, 0.0));
    try {
      // No identifier → fail-closed → should return the caller default (false)
      boolean result = client.getBooleanValue("payment-new-flow", false);
      assertFalse(
          result, "Confidential rollout flag with no identifier must return caller default");
    } finally {
      client.close();
      cache.close();
    }
  }

  @Test
  @DisplayName("ADR-SDK-004 E2: confidential rollout flag WITH identifier proceeds normally")
  void confidentialRolloutFlagWithIdentifierProceedsNormally() throws Exception {
    String singleFlagResponse =
        "{\"flagKey\":\"fraud-check\",\"enabled\":true,\"value\":\"true\","
            + "\"valueType\":\"BOOLEAN\",\"rolloutPercent\":50}";
    httpServer.createContext(
        "/api/v1/sdk/flags/fraud-check",
        exchange -> {
          byte[] bytes = singleFlagResponse.getBytes();
          exchange.sendResponseHeaders(200, bytes.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
          }
        });

    TtlFlagCache cache = new TtlFlagCache(60, 0);
    // Put rollout entry in cache so we skip fetchAll path
    cache.put(
        "fraud-check",
        new CacheEntry("fraud-check", true, "true", FlagValueType.BOOLEAN, 50, System.nanoTime()));

    FlagClient client = buildClient(cache, new RetryPolicy(0, 1.0, 0, 0.0));
    try {
      // WITH identifier → normal rollout evaluation (server returns true)
      boolean result = client.getBooleanValue("fraud-check", "user-xyz", false);
      assertTrue(result, "Confidential rollout flag with identifier must evaluate normally");
    } finally {
      client.close();
      cache.close();
    }
  }

  @Test
  @DisplayName(
      "ADR-SDK-004 E2: non-confidential rollout flag without identifier evaluates normally")
  void nonConfidentialRolloutFlagNoIdentifierEvaluatesNormally() throws Exception {
    String singleFlagResponse =
        "{\"flagKey\":\"beta-ui\",\"enabled\":true,\"value\":\"true\","
            + "\"valueType\":\"BOOLEAN\",\"rolloutPercent\":50}";
    httpServer.createContext(
        "/api/v1/sdk/flags/beta-ui",
        exchange -> {
          byte[] bytes = singleFlagResponse.getBytes();
          exchange.sendResponseHeaders(200, bytes.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
          }
        });

    TtlFlagCache cache = new TtlFlagCache(60, 0);
    cache.put(
        "beta-ui",
        new CacheEntry("beta-ui", true, "true", FlagValueType.BOOLEAN, 50, System.nanoTime()));

    FlagClient client = buildClient(cache, new RetryPolicy(0, 1.0, 0, 0.0));
    try {
      // No identifier but non-confidential key → normal evaluation (server says true)
      boolean result = client.getBooleanValue("beta-ui", false);
      assertTrue(result, "Non-confidential rollout flag must not be fail-closed");
    } finally {
      client.close();
      cache.close();
    }
  }

  // ---------------------------------------------------------------------------
  // FlagClient.close()
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("close() is idempotent")
  void closeIsIdempotent() throws Exception {
    FlagClient client = buildClientWithFreshCache();
    assertDoesNotThrow(client::close);
    assertDoesNotThrow(client::close);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private FlagClient buildClientWithFreshCache() {
    SdkConfig cfg =
        SdkConfig.builder()
            .serverUrl("http://127.0.0.1:" + serverPort)
            .apiKey(SYNTHETIC_KEY)
            .cacheTtlSeconds(60)
            .connectTimeoutMs(3000)
            .readTimeoutMs(3000)
            .buildUnchecked();
    TtlFlagCache cache = new TtlFlagCache(cfg.getCacheTtlSeconds(), cfg.getMaxStaleSeconds());
    java.net.http.HttpClient plainHttp = java.net.http.HttpClient.newHttpClient();
    JdkFlagHttpClient httpClient = new JdkFlagHttpClient(cfg, plainHttp);
    return new FlagClient(cfg, httpClient, cache, new DiagnosticsCollector(), RetryPolicy.DEFAULT);
  }

  private FlagClient buildClient(FlagCache cache, RetryPolicy retryPolicy) {
    SdkConfig cfg =
        SdkConfig.builder()
            .serverUrl("http://127.0.0.1:" + serverPort)
            .apiKey(SYNTHETIC_KEY)
            .cacheTtlSeconds(60)
            .connectTimeoutMs(3000)
            .readTimeoutMs(3000)
            .buildUnchecked();
    java.net.http.HttpClient plainHttp = java.net.http.HttpClient.newHttpClient();
    JdkFlagHttpClient httpClient = new JdkFlagHttpClient(cfg, plainHttp);
    return new FlagClient(cfg, httpClient, cache, new DiagnosticsCollector(), retryPolicy);
  }
}
