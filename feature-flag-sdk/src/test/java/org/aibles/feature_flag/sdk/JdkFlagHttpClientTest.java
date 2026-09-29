package org.aibles.feature_flag.sdk;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.aibles.feature_flag.sdk.exception.InvalidApiKeyException;
import org.aibles.feature_flag.sdk.internal.CacheEntry;
import org.aibles.feature_flag.sdk.internal.SdkConfig;
import org.aibles.feature_flag.sdk.internal.TestSdkConfigHelper;
import org.aibles.feature_flag.sdk.internal.http.JdkFlagHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@link JdkFlagHttpClient}: JSON parsing, identifier as query param,
 * fetchAll, non-200 handling.
 */
class JdkFlagHttpClientTest {

  /** Synthetic API key — never a real credential. */
  private static final String SYNTHETIC_KEY =
      "syn-http-test-key-000000000000000000000000000000000000000000000000000000000";

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
  // fetchOne — JSON parsing
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("fetchOne parses a boolean flag JSON response into a CacheEntry")
  void fetchOneParsesBooleanFlag() throws Exception {
    String responseBody =
        """
        {
          "flagKey": "dark-launch",
          "enabled": true,
          "value": "true",
          "valueType": "BOOLEAN",
          "rolloutPercent": 0
        }
        """;
    httpServer.createContext(
        "/api/v1/sdk/flags/dark-launch",
        exchange -> {
          byte[] bytes = responseBody.getBytes();
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
          }
        });

    JdkFlagHttpClient client = buildClient();
    CacheEntry entry = client.fetchOne("dark-launch", null);

    assertNotNull(entry);
    assertEquals("dark-launch", entry.getFlagKey());
    assertTrue(entry.isEnabled());
    assertEquals("true", entry.getValue());
    assertEquals(FlagValueType.BOOLEAN, entry.getValueType());
    assertEquals(0, entry.getRolloutPercent());
  }

  @Test
  @DisplayName("fetchOne parses a rollout flag (rolloutPercent=50) correctly")
  void fetchOneParsesRolloutFlag() throws Exception {
    String responseBody =
        """
        {
          "flagKey": "feature-x",
          "enabled": true,
          "value": "true",
          "valueType": "BOOLEAN",
          "rolloutPercent": 50
        }
        """;
    httpServer.createContext(
        "/api/v1/sdk/flags/feature-x",
        exchange -> {
          byte[] bytes = responseBody.getBytes();
          exchange.sendResponseHeaders(200, bytes.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
          }
        });

    JdkFlagHttpClient client = buildClient();
    CacheEntry entry = client.fetchOne("feature-x", "user-42");

    assertNotNull(entry);
    assertEquals(50, entry.getRolloutPercent());
    assertTrue(entry.isRollout());
  }

  @Test
  @DisplayName("fetchOne returns null on 404 (no-retry, degrade)")
  void fetchOneReturnsNullOn404() throws Exception {
    httpServer.createContext(
        "/api/v1/sdk/flags/missing-flag",
        exchange -> {
          exchange.sendResponseHeaders(404, -1);
          exchange.getResponseBody().close();
        });

    JdkFlagHttpClient client = buildClient();
    // fetchOne with no retry policy — raw method just returns null on non-200
    CacheEntry result = client.fetchOne("missing-flag", null);
    assertNull(result, "fetchOne must return null on 404");
  }

  @Test
  @DisplayName("fetchOne throws InvalidApiKeyException on 401")
  void fetchOneThrows401() throws Exception {
    httpServer.createContext(
        "/api/v1/sdk/flags/any-flag",
        exchange -> {
          exchange.sendResponseHeaders(401, -1);
          exchange.getResponseBody().close();
        });

    JdkFlagHttpClient client = buildClient();
    assertThrows(InvalidApiKeyException.class, () -> client.fetchOne("any-flag", null));
  }

  // ---------------------------------------------------------------------------
  // fetchOne — identifier is sent as QUERY PARAM (not header)
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("fetchOne sends identifier as ?identifier= query param (NOT as header)")
  void fetchOneSendsIdentifierAsQueryParam() throws Exception {
    AtomicReference<URI> capturedUri = new AtomicReference<>();
    AtomicReference<String> capturedIdentifierHeader = new AtomicReference<>();

    String responseBody =
        "{\"flagKey\":\"checkout-v2\",\"enabled\":true,\"value\":\"true\","
            + "\"valueType\":\"BOOLEAN\",\"rolloutPercent\":0}";

    httpServer.createContext(
        "/api/v1/sdk/flags/checkout-v2",
        exchange -> {
          capturedUri.set(exchange.getRequestURI());
          capturedIdentifierHeader.set(exchange.getRequestHeaders().getFirst("X-Flag-Identifier"));
          byte[] bytes = responseBody.getBytes();
          exchange.sendResponseHeaders(200, bytes.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
          }
        });

    JdkFlagHttpClient client = buildClient();
    client.fetchOne("checkout-v2", "user-123");

    // ASSERT: identifier appears in the query string
    assertNotNull(capturedUri.get(), "URI must have been captured");
    String query = capturedUri.get().getQuery();
    assertNotNull(query, "Request URI must have a query string");
    assertTrue(
        query.contains("identifier=user-123") || query.contains("identifier=user%2D123"),
        "Request URI must contain identifier as query param, got: " + query);

    // ASSERT: identifier is NOT sent as X-Flag-Identifier header
    assertNull(
        capturedIdentifierHeader.get(),
        "X-Flag-Identifier header must NOT be set (identifier moved to query param)");
  }

  @Test
  @DisplayName("fetchAll sends identifier as ?identifier= query param")
  void fetchAllSendsIdentifierAsQueryParam() throws Exception {
    AtomicReference<URI> capturedUri = new AtomicReference<>();

    String responseBody =
        "[{\"flagKey\":\"f1\",\"enabled\":true,\"value\":\"true\","
            + "\"valueType\":\"BOOLEAN\",\"rolloutPercent\":0}]";

    httpServer.createContext(
        "/api/v1/sdk/flags",
        exchange -> {
          capturedUri.set(exchange.getRequestURI());
          byte[] bytes = responseBody.getBytes();
          exchange.sendResponseHeaders(200, bytes.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
          }
        });

    JdkFlagHttpClient client = buildClient();
    client.fetchAll("user-abc");

    assertNotNull(capturedUri.get());
    String query = capturedUri.get().getQuery();
    assertNotNull(query, "fetchAll must include identifier in query string");
    assertTrue(
        query.contains("identifier="),
        "fetchAll query must contain identifier param, got: " + query);
  }

  // ---------------------------------------------------------------------------
  // fetchAll — JSON parsing
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("fetchAll parses a JSON array into a list of CacheEntry objects")
  void fetchAllParsesJsonArray() throws Exception {
    String responseBody =
        """
        [
          {"flagKey":"flag-1","enabled":true,"value":"true","valueType":"BOOLEAN","rolloutPercent":0},
          {"flagKey":"flag-2","enabled":false,"value":null,"valueType":"STRING","rolloutPercent":0},
          {"flagKey":"flag-3","enabled":true,"value":"42","valueType":"INTEGER","rolloutPercent":25}
        ]
        """;
    httpServer.createContext(
        "/api/v1/sdk/flags",
        exchange -> {
          byte[] bytes = responseBody.getBytes();
          exchange.sendResponseHeaders(200, bytes.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
          }
        });

    JdkFlagHttpClient client = buildClient();
    List<CacheEntry> entries = client.fetchAll(null);

    assertEquals(3, entries.size());
    assertEquals("flag-1", entries.get(0).getFlagKey());
    assertTrue(entries.get(0).isEnabled());
    assertEquals("flag-2", entries.get(1).getFlagKey());
    assertFalse(entries.get(1).isEnabled());
    assertEquals("flag-3", entries.get(2).getFlagKey());
    assertEquals(FlagValueType.INTEGER, entries.get(2).getValueType());
    assertEquals(25, entries.get(2).getRolloutPercent());
    assertTrue(entries.get(2).isRollout());
  }

  @Test
  @DisplayName("fetchAll throws IOException on 500 server error (HF-5: signals retryable error)")
  void fetchAllThrowsIOExceptionOn500() throws Exception {
    httpServer.createContext(
        "/api/v1/sdk/flags",
        exchange -> {
          exchange.sendResponseHeaders(500, -1);
          exchange.getResponseBody().close();
        });

    JdkFlagHttpClient client = buildClient();
    // HF-5 fix: fetchAll now throws IOException on non-200 so the FlagClient retry loop fires.
    assertThrows(
        IOException.class,
        () -> client.fetchAll(null),
        "fetchAll must throw IOException on 500 so retry loop and serverError counter engage");
  }

  @Test
  @DisplayName("fetchAll throws InvalidApiKeyException on 401")
  void fetchAllThrows401() throws Exception {
    httpServer.createContext(
        "/api/v1/sdk/flags",
        exchange -> {
          exchange.sendResponseHeaders(401, -1);
          exchange.getResponseBody().close();
        });

    JdkFlagHttpClient client = buildClient();
    assertThrows(InvalidApiKeyException.class, () -> client.fetchAll(null));
  }

  @Test
  @DisplayName("fetchOne omits identifier query param when identifier is null")
  void fetchOneOmitsIdentifierWhenNull() throws Exception {
    AtomicReference<URI> capturedUri = new AtomicReference<>();
    String responseBody =
        "{\"flagKey\":\"f1\",\"enabled\":true,\"value\":\"true\","
            + "\"valueType\":\"BOOLEAN\",\"rolloutPercent\":0}";

    httpServer.createContext(
        "/api/v1/sdk/flags/f1",
        exchange -> {
          capturedUri.set(exchange.getRequestURI());
          byte[] bytes = responseBody.getBytes();
          exchange.sendResponseHeaders(200, bytes.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
          }
        });

    JdkFlagHttpClient client = buildClient();
    client.fetchOne("f1", null);

    URI uri = capturedUri.get();
    assertNotNull(uri);
    // Query must be absent or not contain 'identifier'
    String query = uri.getQuery();
    if (query != null) {
      assertFalse(
          query.contains("identifier"),
          "Should not send identifier= when identifier is null, got: " + query);
    }
    // null query is fine — no query string sent
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private JdkFlagHttpClient buildClient() {
    SdkConfig cfg =
        TestSdkConfigHelper.buildUnchecked(
            SdkConfig.builder()
                .serverUrl("http://127.0.0.1:" + serverPort)
                .apiKey(SYNTHETIC_KEY)
                .cacheTtlSeconds(60)
                .connectTimeoutMs(3000)
                .readTimeoutMs(3000));
    java.net.http.HttpClient plainClient = java.net.http.HttpClient.newHttpClient();
    return new JdkFlagHttpClient(cfg, plainClient);
  }
}
