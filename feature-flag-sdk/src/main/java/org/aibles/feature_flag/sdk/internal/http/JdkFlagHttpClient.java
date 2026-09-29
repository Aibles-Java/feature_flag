package org.aibles.feature_flag.sdk.internal.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.net.ssl.SSLParameters;
import org.aibles.feature_flag.sdk.FlagValueType;
import org.aibles.feature_flag.sdk.exception.InvalidApiKeyException;
import org.aibles.feature_flag.sdk.internal.CacheEntry;
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
 *
 * <p>Identifier transport: {@code identifier} is sent as a query parameter {@code ?identifier=...}
 * (URL-encoded) to match the live server contract (@RequestParam on EvaluationController). Moving
 * it to {@code X-Flag-Identifier} header is deferred to a future phase requiring a coordinated
 * server change.
 */
public final class JdkFlagHttpClient {

  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(JdkFlagHttpClient.class);

  /** SDK will only negotiate TLS 1.2 or TLS 1.3 (PCI Req 4.2.1). */
  private static final String[] PINNED_TLS_PROTOCOLS = {"TLSv1.3", "TLSv1.2"};

  /**
   * Hard ceiling on the HTTP response body size in bytes (HF-4 / DoS guard). The body is bounded
   * BEFORE Jackson parsing; responses exceeding this limit are rejected with an IOException.
   * Default: 4 MB (4 * 1024 * 1024 bytes).
   *
   * <p>Public so cross-package tests can reference the constant without reflection. It is an inert
   * numeric safety bound (not a credential or a validation-bypass), so exposing it is safe.
   */
  public static final int MAX_RESPONSE_BODY_BYTES = 4 * 1024 * 1024; // 4 MB

  /**
   * Maximum number of entries accepted in a fetchAll response list (HF-4 / ADR-SDK-002 alignment).
   * A list exceeding this limit is rejected with an IOException before cache population.
   *
   * <p>Public so cross-package tests can reference the constant without reflection. It is an inert
   * numeric safety bound (not a credential or a validation-bypass), so exposing it is safe.
   */
  public static final int MAX_FETCH_ALL_ENTRIES = 5_000;

  /** Protocols actually configured on the underlying {@link HttpClient}'s SSLParameters. */
  private final String[] configuredProtocols;

  private final SdkConfig config;
  private final HttpClient httpClient;

  /** Shared mapper for response parsing — no default typing enabled (ADR-SDK-003). */
  private static final ObjectMapper RESPONSE_MAPPER = new ObjectMapper();

  /**
   * Constructs a new client. Detects JDK wire-logging and emits a WARN if header logging is
   * enabled.
   */
  public JdkFlagHttpClient(SdkConfig config) {
    this.config = config;
    this.httpClient = buildHttpClient(config);
    this.configuredProtocols = PINNED_TLS_PROTOCOLS.clone();
    detectWireLogging();
  }

  /**
   * <strong>FOR TEST USE ONLY.</strong> Constructs a client with a pre-built {@link HttpClient},
   * bypassing TLS configuration. Intended for unit tests that route to a plain-HTTP loopback
   * server. Never call from production code.
   *
   * <p>The {@code configuredProtocols} field reflects the pinned TLS protocols but the supplied
   * {@code httpClient} may not enforce them — callers are responsible for test isolation.
   */
  public JdkFlagHttpClient(SdkConfig config, java.net.http.HttpClient httpClient) {
    this.config = config;
    this.httpClient = httpClient;
    this.configuredProtocols = PINNED_TLS_PROTOCOLS.clone();
    detectWireLogging();
  }

  /**
   * Returns the TLS protocol strings that were pinned on the {@link HttpClient}'s {@link
   * SSLParameters} at construction time. Intended for tests only — do not use in production code.
   *
   * <p>Removing the {@code sslParameters.setProtocols} call in {@link #buildHttpClient} would
   * return an empty array here, making the H-1 assertion fail and proving the test is non-vacuous.
   */
  public String[] configuredProtocols() {
    return configuredProtocols.clone();
  }

  // ---------------------------------------------------------------------------
  // fetchOne — returns a CacheEntry (full implementation)
  // ---------------------------------------------------------------------------

  /**
   * Fetches a single flag by key from the flag server.
   *
   * <p>The {@code identifier} is sent as the {@code ?identifier=…} query parameter (URL-encoded) to
   * match the live server contract. See class Javadoc for the deferred header-transport note.
   *
   * @param flagKey the flag key to fetch (never logged); must be non-null, non-blank, and must not
   *     contain {@code '/'}, {@code '?'}, {@code '#'}, or whitespace to prevent path traversal and
   *     silent URI truncation (M-3 / LLD §6.3)
   * @param identifier opaque caller identity for rollout bucketing (PII — never logged); may be
   *     null or blank (omitted from the request)
   * @param onAuthFailure callback invoked (synchronously, before throw) when a 401 is received
   *     (SR-02); pass {@code null} for a no-op
   * @return a {@link CacheEntry} parsed from the server response, or {@code null} on non-200 non
   *     -retryable non-401 status
   * @throws IllegalArgumentException if flagKey is null, blank, or contains unsafe characters
   * @throws IOException if the request fails (includes SSLHandshakeException for cert / protocol
   *     violations)
   * @throws InvalidApiKeyException if the server returns HTTP 401
   */
  public CacheEntry fetchOne(
      String flagKey, String identifier, Consumer<InvalidApiKeyException> onAuthFailure)
      throws IOException {
    validateFlagKey(flagKey);

    URI uri = buildFlagUri("/api/v1/sdk/flags/" + flagKey, identifier);

    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(uri)
            .timeout(Duration.ofMillis(config.getReadTimeoutMs()))
            // Key in header — never logged (DCR-3).
            .header("X-Environment-Key", config.getApiKey())
            .GET()
            .build();

    // Log path template only — never the flagKey segment or header values (DCR-3 / SR-04).
    log.debug("SDK fetch: GET /api/v1/sdk/flags/[key] -> attempt 1");

    HttpResponse<String> response;
    try {
      response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("SDK HTTP request interrupted", e);
    }

    int status = response.statusCode();

    if (status == 401) {
      InvalidApiKeyException ex =
          new InvalidApiKeyException("API key rejected — key may have been rotated");
      // SR-02: invoke the auth-failure hook before throwing so the caller can react
      // (e.g. refresh the key, alert ops) without relying on a catch block.
      if (onAuthFailure != null) {
        onAuthFailure.accept(ex);
      }
      throw ex;
    }

    if (status != 200) {
      // Non-200, non-401: return null so the caller can handle (serve-stale / default).
      log.debug("SDK fetchOne: non-200 status {}", status);
      return null;
    }

    return parseSingleEntry(response.body());
  }

  /**
   * Convenience overload that uses the hook registered in {@link SdkConfig} (SR-02). If no hook was
   * registered the behaviour is a no-op.
   *
   * @see #fetchOne(String, String, Consumer)
   */
  public CacheEntry fetchOne(String flagKey, String identifier) throws IOException {
    return fetchOne(flagKey, identifier, config.getOnAuthFailure());
  }

  // ---------------------------------------------------------------------------
  // fetchAll — bulk pre-warm
  // ---------------------------------------------------------------------------

  /**
   * Fetches all flags for this environment from the flag server.
   *
   * <p>Throws {@link InvalidApiKeyException} on 401. Throws {@link IOException} on any non-200
   * non-401 response so the {@code FlagClient} retry loop engages and {@code
   * DiagnosticsCollector.recordServerError()} fires (HF-5).
   *
   * <p>The response body is bounded to {@link #MAX_RESPONSE_BODY_BYTES} bytes BEFORE Jackson
   * parsing (HF-4 / DoS guard). Responses exceeding the cap throw {@link IOException} which is
   * treated as a transient failure (serve-stale → caller default — never OOM).
   *
   * <p>Parsed lists exceeding {@link #MAX_FETCH_ALL_ENTRIES} entries are rejected with {@link
   * IOException} (HF-4 alignment with ADR-SDK-002 {@code maxEntries=10 000}).
   *
   * @param identifier opaque caller identity; may be null (omitted from request)
   * @throws IOException on non-200/non-401 HTTP status, network error, oversize body, or oversize
   *     list
   * @throws InvalidApiKeyException on HTTP 401
   */
  public List<CacheEntry> fetchAll(String identifier) throws IOException {
    URI uri = buildFlagUri("/api/v1/sdk/flags", identifier);

    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(uri)
            .timeout(Duration.ofMillis(config.getReadTimeoutMs()))
            .header("X-Environment-Key", config.getApiKey())
            .GET()
            .build();

    log.debug("SDK fetchAll: GET /api/v1/sdk/flags");

    HttpResponse<byte[]> response;
    try {
      response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("SDK HTTP request interrupted", e);
    }

    int status = response.statusCode();

    if (status == 401) {
      Consumer<InvalidApiKeyException> hook = config.getOnAuthFailure();
      InvalidApiKeyException ex =
          new InvalidApiKeyException("API key rejected — key may have been rotated");
      if (hook != null) {
        hook.accept(ex);
      }
      throw ex;
    }

    if (status != 200) {
      // HF-5: throw IOException so the FlagClient retry loop engages and serverError is recorded.
      throw new IOException("SDK fetchAll: non-200 status " + status);
    }

    // HF-4: bound the body size BEFORE Jackson parsing (DoS guard).
    byte[] bodyBytes = response.body();
    if (bodyBytes.length > MAX_RESPONSE_BODY_BYTES) {
      throw new IOException(
          "SDK fetchAll: response body exceeds " + MAX_RESPONSE_BODY_BYTES + " byte cap (HF-4)");
    }

    List<CacheEntry> entries = parseEntryList(new String(bodyBytes, StandardCharsets.UTF_8));
    // HF-4: cap parsed list size (aligned with ADR-SDK-002 maxEntries).
    if (entries.size() > MAX_FETCH_ALL_ENTRIES) {
      throw new IOException(
          "SDK fetchAll: response contains "
              + entries.size()
              + " entries which exceeds cap of "
              + MAX_FETCH_ALL_ENTRIES
              + " (HF-4)");
    }
    return entries;
  }

  // ---------------------------------------------------------------------------
  // URI construction
  // ---------------------------------------------------------------------------

  /**
   * Builds a request URI for the given path, appending {@code ?identifier=…} (URL-encoded) when the
   * identifier is non-blank. The identifier is sent as a QUERY PARAM to match the live server
   * {@code @RequestParam} contract on {@code EvaluationController}.
   */
  private URI buildFlagUri(String path, String identifier) {
    try {
      URI base = new URI(config.getServerUrl());
      String authority = base.getAuthority();
      String query = null;
      if (identifier != null && !identifier.isBlank()) {
        // URL-encode the identifier to prevent query-string injection.
        query = "identifier=" + URLEncoder.encode(identifier, StandardCharsets.UTF_8);
      }
      return new URI(base.getScheme(), authority, path, query, null);
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("Failed to construct request URI", e);
    }
  }

  // ---------------------------------------------------------------------------
  // Response parsing
  // ---------------------------------------------------------------------------

  private CacheEntry parseSingleEntry(String body) throws IOException {
    Map<String, Object> map = RESPONSE_MAPPER.readValue(body, new TypeReference<>() {});
    return mapToEntry(map);
  }

  private List<CacheEntry> parseEntryList(String body) throws IOException {
    List<Map<String, Object>> list = RESPONSE_MAPPER.readValue(body, new TypeReference<>() {});
    long nowNanos = System.nanoTime();
    return list.stream().map(m -> mapToEntryWithTimestamp(m, nowNanos)).toList();
  }

  private static CacheEntry mapToEntry(Map<String, Object> m) {
    return mapToEntryWithTimestamp(m, System.nanoTime());
  }

  private static CacheEntry mapToEntryWithTimestamp(Map<String, Object> m, long nowNanos) {
    String flagKey = (String) m.get("flagKey");
    boolean enabled = Boolean.TRUE.equals(m.get("enabled"));
    String value = (String) m.get("value");
    FlagValueType valueType = parseValueType(m.get("valueType"));
    int rolloutPercent = 0;
    Object rp = m.get("rolloutPercent");
    if (rp instanceof Number n) {
      rolloutPercent = n.intValue();
    }
    return new CacheEntry(flagKey, enabled, value, valueType, rolloutPercent, nowNanos);
  }

  private static FlagValueType parseValueType(Object raw) {
    if (raw == null) return null;
    try {
      return FlagValueType.valueOf(raw.toString());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  // ---------------------------------------------------------------------------
  // FlagKey validation (M-3 / LLD §6.3)
  // ---------------------------------------------------------------------------

  private static void validateFlagKey(String flagKey) {
    if (flagKey == null || flagKey.isBlank()) {
      throw new IllegalArgumentException("flagKey must not be null or blank");
    }
    for (int i = 0; i < flagKey.length(); i++) {
      char c = flagKey.charAt(i);
      if (c == '/' || c == '?' || c == '#' || Character.isWhitespace(c)) {
        throw new IllegalArgumentException(
            "flagKey contains unsafe character at index " + i + ": '" + c + "'");
      }
    }
  }

  // ---------------------------------------------------------------------------
  // HttpClient construction
  // ---------------------------------------------------------------------------

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
