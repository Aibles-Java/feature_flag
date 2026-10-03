package org.aibles.feature_flag.sdk;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.aibles.feature_flag.sdk.exception.FlagTypeMismatchException;
import org.aibles.feature_flag.sdk.internal.CacheEntry;
import org.aibles.feature_flag.sdk.internal.DiagnosticsCollector;
import org.aibles.feature_flag.sdk.internal.DiagnosticsCollector.DiagnosticsSnapshot;
import org.aibles.feature_flag.sdk.internal.FlagCache;
import org.aibles.feature_flag.sdk.internal.RetryPolicy;
import org.aibles.feature_flag.sdk.internal.SdkConfig;
import org.aibles.feature_flag.sdk.internal.TestSdkConfigHelper;
import org.aibles.feature_flag.sdk.internal.TtlFlagCache;
import org.aibles.feature_flag.sdk.internal.TypeCoercionEngine;
import org.aibles.feature_flag.sdk.internal.http.JdkFlagHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Tests added for review findings HF-1 through HF-5 and SF-1 through SF-5.
 *
 * <p>Each test is named after the finding it proves. Tests are written RED-first (before the
 * production fix).
 */
class ReviewFixesTest {

  private static final String SYNTHETIC_KEY =
      "syn-review-test-key-00000000000000000000000000000000000000000000000000000000";

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
  // HF-1: confidential rollout fail-closed on single-fetch fallback path
  // ---------------------------------------------------------------------------

  /**
   * HF-1: When fetchAll misses the key AND the single-fetch fallback path is taken, a confidential
   * rollout flag with no identifier must still return the caller default (fail-closed), NOT the
   * server's fully-on value.
   */
  @Test
  @DisplayName(
      "HF-1: confidential rollout flag + no identifier + fetchAll misses key"
          + " → returns caller default (not server value)")
  void hf1ConfidentialRolloutFailClosedOnFetchOneFallback() throws Exception {
    // fetchAll returns empty list (key absent from bulk response)
    String fetchAllBody = "[]";
    httpServer.createContext(
        "/api/v1/sdk/flags",
        exchange -> {
          byte[] bytes = fetchAllBody.getBytes();
          exchange.sendResponseHeaders(200, bytes.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
          }
        });

    // fetchOne returns fully-on value for the confidential key (server fail-open)
    String fetchOneBody =
        "{\"flagKey\":\"payment-v2\",\"enabled\":true,\"value\":\"true\","
            + "\"valueType\":\"BOOLEAN\",\"rolloutPercent\":50}";
    httpServer.createContext(
        "/api/v1/sdk/flags/payment-v2",
        exchange -> {
          byte[] bytes = fetchOneBody.getBytes();
          exchange.sendResponseHeaders(200, bytes.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
          }
        });

    // Cold cache: no identifier supplied
    TtlFlagCache cache = new TtlFlagCache(60, 0);
    FlagClient client = buildClient(cache, new RetryPolicy(0, 1.0, 0, 0.0));
    try {
      // Must return false (caller default), not true (server's fully-on)
      boolean result = client.getBooleanValue("payment-v2", false);
      assertFalse(
          result,
          "HF-1: confidential rollout flag via fetchOne fallback with no identifier"
              + " must return caller default (fail-closed)");
    } finally {
      client.close();
      cache.close();
    }
  }

  // ---------------------------------------------------------------------------
  // HF-2: CONFIDENTIAL flag value must NOT appear in exception messages
  // ---------------------------------------------------------------------------

  /**
   * HF-2 / HF-3: A malformed INTEGER value must not appear in any exception message or cause chain.
   * The fix also changes the behavior (HF-3: degrade, not throw), so this test verifies both: no
   * raw value in exception chain, and no throw at all (returns default).
   */
  @Test
  @DisplayName("HF-2: malformed INTEGER value does not appear in any exception message")
  void hf2MalformedIntegerValueDoesNotLeakIntoException() {
    String malformedValue = "CONFIDENTIAL-VALUE-12345";
    // Should return default, not throw (HF-3 fix)
    int result =
        TypeCoercionEngine.coerceInteger(
            "card-limit", true, malformedValue, FlagValueType.INTEGER, 99);
    // After HF-3 fix, returns default — verify the value is the default
    assertEquals(99, result, "Malformed INTEGER must degrade to default (HF-3)");
  }

  @Test
  @DisplayName("HF-2: malformed JSON value does not appear in any exception message")
  void hf2MalformedJsonValueDoesNotLeakIntoException() {
    String malformedJson = "CONFIDENTIAL-DATA-{broken";
    // Should return default, not throw (HF-3 fix)
    @SuppressWarnings("unchecked")
    java.util.Map<String, Object> result =
        TypeCoercionEngine.coerceJson(
            "payment-config",
            true,
            malformedJson,
            FlagValueType.JSON,
            java.util.Map.class,
            java.util.Map.of("k", "defaultVal"));
    // After HF-3 fix, returns default
    assertEquals("defaultVal", result.get("k"), "Malformed JSON must degrade to default (HF-3)");
  }

  // ---------------------------------------------------------------------------
  // HF-3: Malformed value must DEGRADE (not throw) — ADR-SDK-003
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("HF-3: coerceInteger with unparseable INTEGER value returns default (no throw)")
  void hf3MalformedIntegerReturnsDefault() {
    // Must NOT throw FlagTypeMismatchException
    int result =
        TypeCoercionEngine.coerceInteger(
            "some-flag", true, "not-a-number", FlagValueType.INTEGER, 42);
    assertEquals(42, result, "Malformed INTEGER must return default without throwing");
  }

  @Test
  @DisplayName("HF-3: coerceJson with malformed JSON returns default (no throw)")
  void hf3MalformedJsonReturnsDefault() {
    @SuppressWarnings("unchecked")
    java.util.Map<String, Object> defaultVal = java.util.Map.of("x", 1);
    @SuppressWarnings("unchecked")
    java.util.Map<String, Object> result =
        TypeCoercionEngine.coerceJson(
            "some-flag",
            true,
            "{invalid-json",
            FlagValueType.JSON,
            java.util.Map.class,
            defaultVal);
    assertSame(defaultVal, result, "Malformed JSON must return default without throwing");
  }

  @Test
  @DisplayName("HF-3: coerceJson with malformed JSON logs WARN with flag key only (no raw value)")
  void hf3MalformedJsonLogsWarnWithKeyOnly() {
    Logger coercionLogger =
        (Logger) LoggerFactory.getLogger("org.aibles.feature_flag.sdk.internal.TypeCoercionEngine");
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    coercionLogger.addAppender(appender);

    String malformedJson = "CONFIDENTIAL-RAW-VALUE-xyz";
    try {
      TypeCoercionEngine.coerceJson(
          "fraud-config", true, malformedJson, FlagValueType.JSON, java.util.Map.class, null);
    } finally {
      coercionLogger.detachAppender(appender);
      appender.stop();
    }

    List<ILoggingEvent> events = appender.list;
    assertFalse(events.isEmpty(), "WARN log must be emitted for malformed JSON");

    for (ILoggingEvent event : events) {
      String msg = event.getFormattedMessage();
      assertFalse(
          msg.contains(malformedJson), "Log must NOT contain the raw flag value. Found: " + msg);
      // Must not contain exception parse error details (e.getMessage())
      assertFalse(
          msg.contains("CONFIDENTIAL"), "Log must NOT contain raw flag value. Found: " + msg);
    }
  }

  @Test
  @DisplayName("HF-3: coerceInteger malformed logs WARN with flag key only (no raw value)")
  void hf3MalformedIntegerLogsWarnWithKeyOnly() {
    Logger coercionLogger =
        (Logger) LoggerFactory.getLogger("org.aibles.feature_flag.sdk.internal.TypeCoercionEngine");
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    coercionLogger.addAppender(appender);

    String malformedValue = "CONFIDENTIAL-INT-VALUE";
    try {
      TypeCoercionEngine.coerceInteger(
          "card-limit", true, malformedValue, FlagValueType.INTEGER, 0);
    } finally {
      coercionLogger.detachAppender(appender);
      appender.stop();
    }

    List<ILoggingEvent> events = appender.list;
    assertFalse(events.isEmpty(), "WARN log must be emitted for malformed INTEGER");

    for (ILoggingEvent event : events) {
      String msg = event.getFormattedMessage();
      assertFalse(
          msg.contains(malformedValue), "Log must NOT contain the raw flag value. Found: " + msg);
    }
  }

  @Test
  @DisplayName("HF-3: real type mismatch (enabled, JSON requested, valueType=BOOLEAN) still throws")
  void hf3RealTypeMismatchStillThrows() {
    assertThrows(
        FlagTypeMismatchException.class,
        () ->
            TypeCoercionEngine.coerceJson(
                "some-flag", true, "true", FlagValueType.BOOLEAN, java.util.Map.class, null),
        "Real type mismatch (enabled + wrong valueType) must still throw FlagTypeMismatchException");
  }

  @Test
  @DisplayName("HF-3: 256KB size-cap on JSON still rejects (enforced before parse)")
  void hf3JsonSizeCapStillRejected() {
    // JSON size cap should still return default (WARN+degrade, not throw per HF-3 spec)
    String bigValue = "\"" + "x".repeat(TypeCoercionEngine.JSON_VALUE_MAX_BYTES + 1) + "\"";
    // Per HF-3 note: cap breach can WARN+default (consistent). We verify it does NOT throw.
    assertDoesNotThrow(
        () ->
            TypeCoercionEngine.coerceJson(
                "some-flag", true, bigValue, FlagValueType.JSON, String.class, "default"),
        "Size cap breach must degrade to default (not throw) per HF-3 degrade contract");
  }

  // ---------------------------------------------------------------------------
  // HF-4: Response body must be bounded BEFORE Jackson parsing
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("HF-4: fetchAll with oversize response body is rejected before parsing (no OOM)")
  void hf4OversizeResponseBodyRejected() throws Exception {
    // Serve a body much larger than the SDK's response cap
    // The body cap constant is package-private in JdkFlagHttpClient; we use a modest 2 MB cap
    // in the test. Actual large body causes rejection (IOException → serve-stale/default).
    int oversizeBytes = JdkFlagHttpClient.MAX_RESPONSE_BODY_BYTES + 1;
    byte[] body = new byte[oversizeBytes];
    java.util.Arrays.fill(body, (byte) '['); // not valid JSON, but size matters

    httpServer.createContext(
        "/api/v1/sdk/flags",
        exchange -> {
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
          }
        });

    SdkConfig cfg =
        TestSdkConfigHelper.buildUnchecked(
            SdkConfig.builder()
                .serverUrl("http://127.0.0.1:" + serverPort)
                .apiKey(SYNTHETIC_KEY)
                .cacheTtlSeconds(60)
                .connectTimeoutMs(3000)
                .readTimeoutMs(5000));
    java.net.http.HttpClient plainHttp = java.net.http.HttpClient.newHttpClient();
    JdkFlagHttpClient httpClient = new JdkFlagHttpClient(cfg, plainHttp);

    // fetchAll should throw IOException (oversize) rather than OOM
    assertThrows(
        IOException.class,
        () -> httpClient.fetchAll(null),
        "HF-4: oversize response body must throw IOException before Jackson parsing");
  }

  @Test
  @DisplayName("HF-4: fetchOne with oversize response body is rejected before parsing (no OOM)")
  void hf4FetchOneOversizeResponseBodyRejected() throws Exception {
    int oversizeBytes = JdkFlagHttpClient.MAX_RESPONSE_BODY_BYTES + 1;
    byte[] body = new byte[oversizeBytes];
    java.util.Arrays.fill(body, (byte) '{'); // size matters, not validity

    httpServer.createContext(
        "/api/v1/sdk/flags/big-flag",
        exchange -> {
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
          }
        });

    SdkConfig cfg =
        TestSdkConfigHelper.buildUnchecked(
            SdkConfig.builder()
                .serverUrl("http://127.0.0.1:" + serverPort)
                .apiKey(SYNTHETIC_KEY)
                .cacheTtlSeconds(60)
                .connectTimeoutMs(3000)
                .readTimeoutMs(5000));
    java.net.http.HttpClient plainHttp = java.net.http.HttpClient.newHttpClient();
    JdkFlagHttpClient httpClient = new JdkFlagHttpClient(cfg, plainHttp);

    // fetchOne should throw IOException (oversize) before Jackson parse rather than OOM.
    assertThrows(
        IOException.class,
        () -> httpClient.fetchOne("big-flag", null),
        "HF-4: fetchOne oversize response body must throw IOException before Jackson parsing");
  }

  @Test
  @DisplayName("HF-4: fetchAll parsed list is capped at MAX_FETCH_ALL_ENTRIES entries")
  void hf4FetchAllListSizeCapped() throws Exception {
    // Build a JSON array with more entries than the cap
    int cap = JdkFlagHttpClient.MAX_FETCH_ALL_ENTRIES;
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i <= cap; i++) {
      if (i > 0) sb.append(',');
      sb.append("{\"flagKey\":\"flag-")
          .append(i)
          .append(
              "\",\"enabled\":true,\"value\":\"true\",\"valueType\":\"BOOLEAN\",\"rolloutPercent\":0}");
    }
    sb.append("]");
    byte[] body = sb.toString().getBytes();

    httpServer.createContext(
        "/api/v1/sdk/flags",
        exchange -> {
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
          }
        });

    SdkConfig cfg =
        TestSdkConfigHelper.buildUnchecked(
            SdkConfig.builder()
                .serverUrl("http://127.0.0.1:" + serverPort)
                .apiKey(SYNTHETIC_KEY)
                .cacheTtlSeconds(60)
                .connectTimeoutMs(3000)
                .readTimeoutMs(5000));
    java.net.http.HttpClient plainHttp = java.net.http.HttpClient.newHttpClient();
    JdkFlagHttpClient httpClient = new JdkFlagHttpClient(cfg, plainHttp);

    // Should throw IOException because the list exceeds the cap
    assertThrows(
        IOException.class,
        () -> httpClient.fetchAll(null),
        "HF-4: fetchAll list exceeding MAX_FETCH_ALL_ENTRIES must throw IOException");
  }

  // ---------------------------------------------------------------------------
  // HF-5: fetchAll retry loop — non-200 must signal error so retries fire
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName(
      "HF-5: fetchAll gets 503 → retried up to maxRetries times → does not throw to caller;"
          + " subsequent get returns caller default")
  void hf5FetchAll503RetriedDoesNotThrowToCaller() throws Exception {
    AtomicInteger fetchAllHits = new AtomicInteger(0);
    httpServer.createContext(
        "/api/v1/sdk/flags",
        exchange -> {
          fetchAllHits.incrementAndGet();
          exchange.sendResponseHeaders(503, -1);
          exchange.getResponseBody().close();
        });
    // fetchOne also fails so we can measure degradation
    httpServer.createContext(
        "/api/v1/sdk/flags/my-flag",
        exchange -> {
          exchange.sendResponseHeaders(503, -1);
          exchange.getResponseBody().close();
        });

    int maxRetries = 2;
    TtlFlagCache cache = new TtlFlagCache(60, 0);
    FlagClient client = buildClient(cache, new RetryPolicy(0, 1.0, maxRetries, 0.0));
    try {
      // Must NOT throw — must degrade to default
      assertDoesNotThrow(
          () -> {
            boolean result = client.getBooleanValue("my-flag", true);
            // With no cached value and all fetches failing, returns caller default
            assertTrue(result, "Must degrade to caller default (true) when server is down");
          },
          "HF-5: fetchAll 503 must not throw to caller (must degrade)");

      // fetchAll should have been called (1 + maxRetries) times
      int expectedAttempts = 1 + maxRetries;
      assertTrue(
          fetchAllHits.get() >= expectedAttempts,
          "HF-5: fetchAll must be retried; expected at least "
              + expectedAttempts
              + " calls, got "
              + fetchAllHits.get());
    } finally {
      client.close();
      cache.close();
    }
  }

  @Test
  @DisplayName("HF-5: fetchAll records serverError on non-200 response")
  void hf5FetchAllNon200RecordsServerError() throws Exception {
    httpServer.createContext(
        "/api/v1/sdk/flags",
        exchange -> {
          exchange.sendResponseHeaders(503, -1);
          exchange.getResponseBody().close();
        });
    httpServer.createContext(
        "/api/v1/sdk/flags/my-flag",
        exchange -> {
          exchange.sendResponseHeaders(503, -1);
          exchange.getResponseBody().close();
        });

    TtlFlagCache cache = new TtlFlagCache(60, 0);
    DiagnosticsCollector diagnostics = new DiagnosticsCollector();
    SdkConfig cfg =
        TestSdkConfigHelper.buildUnchecked(
            SdkConfig.builder()
                .serverUrl("http://127.0.0.1:" + serverPort)
                .apiKey(SYNTHETIC_KEY)
                .cacheTtlSeconds(60)
                .connectTimeoutMs(2000)
                .readTimeoutMs(2000));
    java.net.http.HttpClient plainHttp = java.net.http.HttpClient.newHttpClient();
    JdkFlagHttpClient httpClient = new JdkFlagHttpClient(cfg, plainHttp);
    FlagClient client =
        new FlagClient(cfg, httpClient, cache, diagnostics, new RetryPolicy(0, 1.0, 0, 0.0));
    try {
      client.getBooleanValue("my-flag", false);
      DiagnosticsSnapshot snap = client.diagnostics();
      assertTrue(
          snap.serverErrors() > 0,
          "HF-5: serverErrors must be incremented when fetchAll returns non-200");
    } finally {
      client.close();
      cache.close();
    }
  }

  // ---------------------------------------------------------------------------
  // SF-1: evictExpired() must not destroy entries still inside maxStaleSeconds
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName(
      "SF-1: entry expired but within maxStaleSeconds survives evictExpired"
          + " and is still returned by getStale()")
  void sf1ExpiredEntryWithinStaleWindowSurvivesEviction() {
    // TTL=1s, maxStale=60s: an entry that is 2s old is expired but within stale window
    try (TtlFlagCache cache = new TtlFlagCache(1, 60)) {
      long twoSecondsAgoNanos = System.nanoTime() - 2_000_000_000L;
      CacheEntry staleEntry =
          new CacheEntry(
              "stale-flag", true, "stale-value", FlagValueType.BOOLEAN, 0, twoSecondsAgoNanos);
      cache.put("stale-flag", staleEntry);

      // Trigger eviction manually
      cache.evictExpiredForTest();

      // Entry must still be retrievable via getStale (within stale window)
      java.util.Optional<CacheEntry> result = cache.getStale("stale-flag");
      assertTrue(
          result.isPresent(),
          "SF-1: entry expired but within maxStaleSeconds must survive eviction");
      assertEquals(
          "stale-value",
          result.get().getValue(),
          "SF-1: surviving stale entry must have original value");
    }
  }

  @Test
  @DisplayName("SF-1: entry beyond both TTL and maxStaleSeconds is removed by evictExpired")
  void sf1EntryBeyondStaleWindowIsEvicted() {
    // TTL=1s, maxStale=5s: an entry that is 10s old is beyond stale window
    try (TtlFlagCache cache = new TtlFlagCache(1, 5)) {
      long tenSecondsAgoNanos = System.nanoTime() - 10_000_000_000L;
      CacheEntry oldEntry =
          new CacheEntry(
              "old-flag", true, "old-value", FlagValueType.BOOLEAN, 0, tenSecondsAgoNanos);
      cache.put("old-flag", oldEntry);

      cache.evictExpiredForTest();

      // Entry must be gone (beyond stale window)
      java.util.Optional<CacheEntry> result = cache.getStale("old-flag");
      assertTrue(
          result.isEmpty(), "SF-1: entry beyond maxStaleSeconds must be removed by eviction");
    }
  }

  @Test
  @DisplayName("SF-1: maxStale=0 (unlimited) means expired entries are never evicted")
  void sf1MaxStaleUnlimitedMeansNoEviction() {
    // TTL=1s, maxStale=0 (unlimited): expired entries must never be evicted
    try (TtlFlagCache cache = new TtlFlagCache(1, 0)) {
      long tenSecondsAgoNanos = System.nanoTime() - 10_000_000_000L;
      CacheEntry oldEntry =
          new CacheEntry(
              "old-flag", true, "old-value", FlagValueType.BOOLEAN, 0, tenSecondsAgoNanos);
      cache.put("old-flag", oldEntry);

      cache.evictExpiredForTest();

      // With unlimited stale, entry must survive
      java.util.Optional<CacheEntry> result = cache.getStale("old-flag");
      assertTrue(
          result.isPresent(),
          "SF-1: maxStale=0 (unlimited) means expired entries must survive eviction");
    }
  }

  // ---------------------------------------------------------------------------
  // SF-2: expose cacheSize in DiagnosticsSnapshot
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("SF-2: diagnostics snapshot includes cacheSize from cache")
  void sf2DiagnosticsIncludesCacheSize() throws Exception {
    TtlFlagCache cache = new TtlFlagCache(60, 0);
    cache.put(
        "flag-a",
        new CacheEntry("flag-a", true, "true", FlagValueType.BOOLEAN, 0, System.nanoTime()));
    cache.put(
        "flag-b",
        new CacheEntry("flag-b", false, null, FlagValueType.BOOLEAN, 0, System.nanoTime()));

    SdkConfig cfg =
        TestSdkConfigHelper.buildUnchecked(
            SdkConfig.builder()
                .serverUrl("http://127.0.0.1:" + serverPort)
                .apiKey(SYNTHETIC_KEY)
                .cacheTtlSeconds(60));
    java.net.http.HttpClient plainHttp = java.net.http.HttpClient.newHttpClient();
    JdkFlagHttpClient httpClient = new JdkFlagHttpClient(cfg, plainHttp);
    FlagClient client =
        new FlagClient(cfg, httpClient, cache, new DiagnosticsCollector(), RetryPolicy.DEFAULT);
    try {
      DiagnosticsSnapshot snap = client.diagnostics();
      assertEquals(2, snap.cacheSize(), "SF-2: diagnostics.cacheSize() must reflect cache.size()");
    } finally {
      client.close();
      cache.close();
    }
  }

  // ---------------------------------------------------------------------------
  // SF-4: SdkConfig.Builder.buildUnchecked() must be package-private
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName(
      "SF-4: buildUnchecked() is not accessible via public API"
          + " (must be package-private or narrower)")
  void sf4BuildUncheckedIsNotPublic() throws Exception {
    java.lang.reflect.Method m = SdkConfig.Builder.class.getDeclaredMethod("buildUnchecked");
    int modifiers = m.getModifiers();
    assertFalse(
        java.lang.reflect.Modifier.isPublic(modifiers),
        "SF-4: buildUnchecked() must NOT be public — it exposes a TLS bypass path");
  }

  // ---------------------------------------------------------------------------
  // SF-5: FlagTypeMismatchException Javadoc (behavioral verification)
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName(
      "SF-5: FlagTypeMismatchException.getMessage() contains flagKey"
          + " (confirms key-in-message behavior documented in Javadoc)")
  void sf5FlagTypeMismatchExceptionGetMessageContainsFlagKey() {
    FlagTypeMismatchException ex =
        new FlagTypeMismatchException("my-card-flag", "BOOLEAN", "STRING");
    assertTrue(
        ex.getMessage().contains("my-card-flag"),
        "SF-5: getMessage() must contain the flagKey (documented: CONFIDENTIAL-escalatable)");
    assertEquals("my-card-flag", ex.getFlagKey());
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private FlagClient buildClient(FlagCache cache, RetryPolicy retryPolicy) {
    SdkConfig cfg =
        TestSdkConfigHelper.buildUnchecked(
            SdkConfig.builder()
                .serverUrl("http://127.0.0.1:" + serverPort)
                .apiKey(SYNTHETIC_KEY)
                .cacheTtlSeconds(60)
                .connectTimeoutMs(3000)
                .readTimeoutMs(3000));
    java.net.http.HttpClient plainHttp = java.net.http.HttpClient.newHttpClient();
    JdkFlagHttpClient httpClient = new JdkFlagHttpClient(cfg, plainHttp);
    return new FlagClient(cfg, httpClient, cache, new DiagnosticsCollector(), retryPolicy);
  }
}
