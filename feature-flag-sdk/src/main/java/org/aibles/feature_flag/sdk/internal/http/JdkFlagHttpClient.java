package org.aibles.feature_flag.sdk.internal.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import javax.net.ssl.SSLParameters;
import org.aibles.feature_flag.sdk.exception.InvalidApiKeyException;
import org.aibles.feature_flag.sdk.internal.SdkConfig;

/**
 * HTTP transport layer for the Feature Flag SDK, backed by {@link java.net.http.HttpClient}.
 *
 * <p>TLS enforcement (SR-03 / LLD §6.2):
 *
 * <ul>
 *   <li>Protocols are pinned to {@code {"TLSv1.3", "TLSv1.2"}} via {@link SSLParameters}. TLS 1.1
 *       and below are never negotiated regardless of the ambient JVM SSLContext default.
 *   <li>The default (validating) {@link javax.net.ssl.SSLContext} is used — there is no {@code
 *       trustAllCerts} surface. A self-signed or otherwise untrusted certificate causes {@link
 *       javax.net.ssl.SSLHandshakeException} before any request data leaves the socket.
 * </ul>
 *
 * <p>API key redaction (DCR-3 / LLD §6.1): The key is attached as a request header and is never
 * passed to any Logger call. JDK wire-logging detection is performed at construction; if the JVM
 * property {@code jdk.httpclient.HttpClient.log} contains {@code "headers"}, a WARN is emitted but
 * construction is not blocked.
 */
public final class JdkFlagHttpClient {

  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(JdkFlagHttpClient.class);

  /** SDK will only negotiate TLS 1.2 or TLS 1.3 (PCI Req 4.2.1). */
  private static final String[] PINNED_TLS_PROTOCOLS = {"TLSv1.3", "TLSv1.2"};

  private final SdkConfig config;
  private final HttpClient httpClient;

  /**
   * Constructs a new client. Detects JDK wire-logging and emits a WARN if header logging is
   * enabled.
   */
  public JdkFlagHttpClient(SdkConfig config) {
    this.config = config;
    this.httpClient = buildHttpClient(config);
    detectWireLogging();
  }

  /**
   * Fetches a single flag by key from the flag server.
   *
   * <p>This is a minimal stub for G1 evidence purposes — it performs the HTTP/TLS handshake so the
   * TLS tests can validate behaviour. Full response parsing is a P4 deliverable.
   *
   * @param flagKey the flag key to fetch (never logged)
   * @param identifier opaque caller identity for rollout bucketing (PII — never logged); may be
   *     null
   * @throws IOException if the request fails (includes SSLHandshakeException for cert / protocol
   *     violations)
   * @throws InvalidApiKeyException if the server returns HTTP 401
   */
  public void fetchOne(String flagKey, String identifier) throws IOException {
    String path = "/api/v1/sdk/flags/" + flagKey;
    HttpRequest.Builder reqBuilder =
        HttpRequest.newBuilder()
            .uri(URI.create(config.getServerUrl() + path))
            .timeout(Duration.ofMillis(config.getReadTimeoutMs()))
            // Key in header — never logged (DCR-3).
            .header("X-Environment-Key", config.getApiKey())
            .GET();

    if (identifier != null && !identifier.isBlank()) {
      // identifier is PII — passed as header, never in URL path or query string (LLD §6.3).
      reqBuilder.header("X-Flag-Identifier", identifier);
    }

    HttpRequest request = reqBuilder.build();

    // Log path template only — never the flagKey segment or header values (DCR-3 / SR-04).
    log.debug("SDK fetch: GET /api/v1/sdk/flags/[key] -> attempt 1");

    HttpResponse<String> response;
    try {
      response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("SDK HTTP request interrupted", e);
    }

    if (response.statusCode() == 401) {
      throw new InvalidApiKeyException("API key rejected — key may have been rotated");
    }
  }

  /**
   * Builds the {@link HttpClient} with TLS protocol pinning. Uses the default (validating) {@link
   * javax.net.ssl.SSLContext} — no trust override.
   */
  private static HttpClient buildHttpClient(SdkConfig config) {
    SSLParameters sslParameters = new SSLParameters();
    sslParameters.setProtocols(PINNED_TLS_PROTOCOLS);

    return HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(config.getConnectTimeoutMs()))
        .sslParameters(sslParameters)
        // No sslContext() override — default JVM trust store is used (cert validation enforced).
        .build();
  }

  /**
   * Detects JDK header wire-logging and emits a security warning (DCR-3 / LLD §6.1). Does NOT throw
   * — the SDK remains functional but the operator is alerted.
   */
  private static void detectWireLogging() {
    String jdkLogProp = System.getProperty("jdk.httpclient.HttpClient.log", "");
    if (jdkLogProp.contains("headers")) {
      log.warn(
          "SECURITY WARNING: JDK HttpClient header logging is enabled"
              + " (jdk.httpclient.HttpClient.log contains 'headers')."
              + " The X-Environment-Key credential will appear in JDK-level logs."
              + " Disable this system property in production.");
    }
  }
}
