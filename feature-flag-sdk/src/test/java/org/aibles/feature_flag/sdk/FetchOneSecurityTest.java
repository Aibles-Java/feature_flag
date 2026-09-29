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
import java.util.concurrent.atomic.AtomicBoolean;
import org.aibles.feature_flag.sdk.exception.InvalidApiKeyException;
import org.aibles.feature_flag.sdk.exception.SdkConfigurationException;
import org.aibles.feature_flag.sdk.internal.SdkConfig;
import org.aibles.feature_flag.sdk.internal.http.JdkFlagHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Security tests for {@link JdkFlagHttpClient#fetchOne}: key-leak prevention (H-2 / DCR-5),
 * onAuthFailure hook (M-1 / SR-02), log-capture (M-2 / DCR-3), and unsafe flagKey rejection (M-3).
 *
 * <p>Uses a plain HTTP loopback server ({@code com.sun.net.httpserver.HttpServer}, standard JDK 21)
 * to avoid TLS setup. TLS enforcement is tested separately in {@link TlsEnforcementTest}. The tests
 * that need to route to the loopback server use the package-private {@link
 * JdkFlagHttpClient#JdkFlagHttpClient(SdkConfig, java.net.http.HttpClient)} constructor (test-only)
 * together with {@link SdkConfig.Builder#buildUnchecked()} (test-only, skips scheme check).
 */
class FetchOneSecurityTest {

  /** Synthetic key — never a real credential. */
  private static final String SYNTHETIC_KEY =
      "syn-fetch-test-key-0000000000000000000000000000000000000000000000000000";

  private HttpServer httpServer;
  private int serverPort;

  @BeforeEach
  void startHttpServer() throws IOException {
    httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    serverPort = httpServer.getAddress().getPort();
    httpServer.start();
  }

  @AfterEach
  void stopHttpServer() {
    if (httpServer != null) {
      httpServer.stop(0);
    }
  }

  // ---------------------------------------------------------------------------
  // H-2: DCR-5 — 401 maps to InvalidApiKeyException; key never leaks
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName(
      "H-2: fetchOne on 401 throws InvalidApiKeyException with approved message;"
          + " API key absent in message/causes/suppressed (DCR-5 / LLD §6.1)")
  void fetchOneOn401ThrowsAndKeyDoesNotLeak() throws Exception {
    httpServer.createContext(
        "/api/v1/sdk/flags/checkout-v2",
        exchange -> {
          byte[] body = "Unauthorized".getBytes();
          exchange.sendResponseHeaders(401, body.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
          }
        });

    JdkFlagHttpClient sdkClient = buildPlainHttpClient("http://127.0.0.1:" + serverPort);

    InvalidApiKeyException thrown =
        assertThrows(
            InvalidApiKeyException.class,
            () -> sdkClient.fetchOne("checkout-v2", null),
            "fetchOne must throw InvalidApiKeyException on HTTP 401");

    // LLD §6.1 — message must be the approved text (never the raw key).
    assertEquals(
        "API key rejected — key may have been rotated",
        thrown.getMessage(),
        "Exception message must be the LLD-approved text");

    // DCR-5: key literal must not appear anywhere in the exception chain.
    assertKeyAbsentInExceptionChain(thrown, SYNTHETIC_KEY);
  }

  // ---------------------------------------------------------------------------
  // M-1 / SR-02: onAuthFailure hook is invoked before exception is thrown
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("M-1 / SR-02: onAuthFailure hook is invoked synchronously before throw")
  void onAuthFailureHookIsInvokedBeforeThrow() throws Exception {
    httpServer.createContext(
        "/api/v1/sdk/flags/dark-launch",
        exchange -> {
          exchange.sendResponseHeaders(401, -1);
          exchange.getResponseBody().close();
        });

    AtomicBoolean hookCalled = new AtomicBoolean(false);

    JdkFlagHttpClient sdkClient = buildPlainHttpClient("http://127.0.0.1:" + serverPort);

    // Hook is passed explicitly via three-arg overload (SR-02).
    assertThrows(
        InvalidApiKeyException.class,
        () -> sdkClient.fetchOne("dark-launch", null, ex -> hookCalled.set(true)),
        "InvalidApiKeyException must still be thrown after hook runs");

    assertTrue(hookCalled.get(), "onAuthFailure hook must have been invoked synchronously");
  }

  @Test
  @DisplayName(
      "M-1 / SR-02: onAuthFailure registered on FlagClientBuilder is used via two-arg overload")
  void onAuthFailureRegisteredOnBuilderIsUsed() throws Exception {
    httpServer.createContext(
        "/api/v1/sdk/flags/builder-hook-flag",
        exchange -> {
          exchange.sendResponseHeaders(401, -1);
          exchange.getResponseBody().close();
        });

    AtomicBoolean hookCalled = new AtomicBoolean(false);

    // buildUnchecked skips https:// scheme check so the plain http:// URL passes.
    SdkConfig cfg =
        SdkConfig.builder()
            .serverUrl("http://127.0.0.1:" + serverPort)
            .apiKey(SYNTHETIC_KEY)
            .cacheTtlSeconds(60)
            .onAuthFailure(ex -> hookCalled.set(true))
            .buildUnchecked();

    java.net.http.HttpClient plainClient = java.net.http.HttpClient.newHttpClient();
    JdkFlagHttpClient sdkClient = new JdkFlagHttpClient(cfg, plainClient);

    assertThrows(
        InvalidApiKeyException.class,
        () -> sdkClient.fetchOne("builder-hook-flag", null),
        "InvalidApiKeyException must be thrown");

    assertTrue(hookCalled.get(), "Hook registered via SdkConfig.onAuthFailure must be invoked");
  }

  // ---------------------------------------------------------------------------
  // M-2: DCR-3 — no API key literal in any log record emitted by fetchOne
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName(
      "M-2: fetchOne emits no log record containing the API key literal (DCR-3 / LLD §6.1)")
  void fetchOneEmitsNoLogRecordContainingApiKey() throws Exception {
    httpServer.createContext(
        "/api/v1/sdk/flags/log-test-flag",
        exchange -> {
          byte[] body = "{}".getBytes();
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
          }
        });

    // Attach a ListAppender to JdkFlagHttpClient's logger (logback-classic required).
    Logger sdkLogger =
        (Logger)
            LoggerFactory.getLogger("org.aibles.feature_flag.sdk.internal.http.JdkFlagHttpClient");
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    sdkLogger.addAppender(appender);

    try {
      JdkFlagHttpClient sdkClient = buildPlainHttpClient("http://127.0.0.1:" + serverPort);
      // A 200 response — no exception thrown.
      sdkClient.fetchOne("log-test-flag", null);

      List<ILoggingEvent> events = appender.list;
      for (ILoggingEvent event : events) {
        String msg = event.getFormattedMessage();
        assertFalse(
            msg.contains(SYNTHETIC_KEY),
            "Log record must not contain the API key literal. Found in: " + msg);
      }
    } finally {
      sdkLogger.detachAppender(appender);
      appender.stop();
    }
  }

  // ---------------------------------------------------------------------------
  // M-3: flagKey validation — unsafe characters and blank/null rejected
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("M-3: fetchOne rejects null flagKey")
  void fetchOneRejectsNullFlagKey() throws SdkConfigurationException {
    JdkFlagHttpClient client =
        new JdkFlagHttpClient(
            SdkConfig.builder()
                .serverUrl("https://flags.example.com")
                .apiKey(SYNTHETIC_KEY)
                .cacheTtlSeconds(60)
                .build());

    assertThrows(IllegalArgumentException.class, () -> client.fetchOne(null, null));
  }

  @Test
  @DisplayName("M-3: fetchOne rejects blank flagKey")
  void fetchOneRejectsBlankFlagKey() throws SdkConfigurationException {
    JdkFlagHttpClient client =
        new JdkFlagHttpClient(
            SdkConfig.builder()
                .serverUrl("https://flags.example.com")
                .apiKey(SYNTHETIC_KEY)
                .cacheTtlSeconds(60)
                .build());

    assertThrows(IllegalArgumentException.class, () -> client.fetchOne("   ", null));
  }

  @Test
  @DisplayName("M-3: fetchOne rejects flagKey containing '/' (path traversal guard)")
  void fetchOneRejectsFlagKeyWithSlash() throws SdkConfigurationException {
    JdkFlagHttpClient client =
        new JdkFlagHttpClient(
            SdkConfig.builder()
                .serverUrl("https://flags.example.com")
                .apiKey(SYNTHETIC_KEY)
                .cacheTtlSeconds(60)
                .build());

    assertThrows(IllegalArgumentException.class, () -> client.fetchOne("../../admin/flags", null));
  }

  @Test
  @DisplayName("M-3: fetchOne rejects flagKey containing '?' (query injection guard)")
  void fetchOneRejectsFlagKeyWithQuestionMark() throws SdkConfigurationException {
    JdkFlagHttpClient client =
        new JdkFlagHttpClient(
            SdkConfig.builder()
                .serverUrl("https://flags.example.com")
                .apiKey(SYNTHETIC_KEY)
                .cacheTtlSeconds(60)
                .build());

    assertThrows(IllegalArgumentException.class, () -> client.fetchOne("flag?inject=1", null));
  }

  @Test
  @DisplayName("M-3: fetchOne rejects flagKey containing '#' (fragment injection guard)")
  void fetchOneRejectsFlagKeyWithHash() throws SdkConfigurationException {
    JdkFlagHttpClient client =
        new JdkFlagHttpClient(
            SdkConfig.builder()
                .serverUrl("https://flags.example.com")
                .apiKey(SYNTHETIC_KEY)
                .cacheTtlSeconds(60)
                .build());

    assertThrows(IllegalArgumentException.class, () -> client.fetchOne("flag#section", null));
  }

  @Test
  @DisplayName("M-3: fetchOne rejects flagKey containing whitespace")
  void fetchOneRejectsFlagKeyWithWhitespace() throws SdkConfigurationException {
    JdkFlagHttpClient client =
        new JdkFlagHttpClient(
            SdkConfig.builder()
                .serverUrl("https://flags.example.com")
                .apiKey(SYNTHETIC_KEY)
                .cacheTtlSeconds(60)
                .build());

    assertThrows(IllegalArgumentException.class, () -> client.fetchOne("flag key", null));
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Builds a {@link JdkFlagHttpClient} that routes to a plain-HTTP loopback server. Uses {@link
   * SdkConfig.Builder#buildUnchecked()} (test-only) to bypass the {@code https://} scheme check,
   * and the package-private {@link JdkFlagHttpClient#JdkFlagHttpClient(SdkConfig,
   * java.net.http.HttpClient)} constructor (test-only) to inject a plain HttpClient.
   */
  private JdkFlagHttpClient buildPlainHttpClient(String serverUrl) {
    SdkConfig cfg =
        SdkConfig.builder()
            .serverUrl(serverUrl)
            .apiKey(SYNTHETIC_KEY)
            .cacheTtlSeconds(60)
            .connectTimeoutMs(3000)
            .readTimeoutMs(3000)
            .buildUnchecked();
    java.net.http.HttpClient plainClient = java.net.http.HttpClient.newHttpClient();
    return new JdkFlagHttpClient(cfg, plainClient);
  }

  /**
   * Asserts that the API key literal does not appear in the exception's message, any cause in the
   * chain, or any suppressed exception (DCR-5 / LLD §6.1).
   */
  private static void assertKeyAbsentInExceptionChain(Throwable thrown, String keyLiteral) {
    Throwable current = thrown;
    while (current != null) {
      String msg = current.getMessage();
      if (msg != null) {
        assertFalse(
            msg.contains(keyLiteral),
            "Exception message must not contain the API key literal. Found in: " + msg);
      }
      for (Throwable suppressed : current.getSuppressed()) {
        String suppressedMsg = suppressed.getMessage();
        if (suppressedMsg != null) {
          assertFalse(
              suppressedMsg.contains(keyLiteral),
              "Suppressed exception must not contain the API key literal. Found in: "
                  + suppressedMsg);
        }
      }
      current = current.getCause();
    }
  }
}
