package org.aibles.feature_flag.sdk.internal;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.function.Consumer;
import org.aibles.feature_flag.sdk.exception.InvalidApiKeyException;
import org.aibles.feature_flag.sdk.exception.SdkConfigurationException;

/**
 * Immutable configuration for the Feature Flag SDK.
 *
 * <p>Security controls (DCR-1 / SC-01):
 *
 * <ul>
 *   <li>{@code apiKey} is annotated {@code @JsonIgnore} — it is never serialised by Jackson.
 *   <li>{@link #toString()} returns {@code apiKey=[REDACTED]} — the raw key literal never appears
 *       in logs or diagnostic output.
 *   <li>This class MUST NOT bear {@code @ToString}, {@code @Data}, or {@code @Value} Lombok
 *       annotations — any of these would regenerate {@code toString()} and include the field.
 * </ul>
 */
public final class SdkConfig {

  /** The flag server base URL (e.g. {@code https://flags.internal}). Classification: INTERNAL. */
  private final String serverUrl;

  /**
   * The environment API key sent as {@code X-Environment-Key}. Classification: CONFIDENTIAL. Never
   * serialised; never logged; never included in toString().
   */
  @JsonIgnore private final String apiKey;

  /** TTL in seconds for cached flag entries. Range: 1–3600. Default: 60. */
  private final int cacheTtlSeconds;

  /**
   * Maximum seconds a stale cache entry may be served while the server is unreachable. 0 = serve
   * stale indefinitely (ADR-SDK-002 default).
   */
  private final int maxStaleSeconds;

  /** HTTP connect timeout in milliseconds. */
  private final int connectTimeoutMs;

  /** HTTP read (response) timeout in milliseconds. */
  private final int readTimeoutMs;

  /**
   * SR-02: callback invoked synchronously when HTTP 401 is received. Never serialised; never
   * logged; never included in toString(). May be null (no-op).
   */
  @JsonIgnore private final Consumer<InvalidApiKeyException> onAuthFailure;

  private SdkConfig(Builder builder) {
    this.serverUrl = builder.serverUrl;
    this.apiKey = builder.apiKey;
    this.cacheTtlSeconds = builder.cacheTtlSeconds;
    this.maxStaleSeconds = builder.maxStaleSeconds;
    this.connectTimeoutMs = builder.connectTimeoutMs;
    this.readTimeoutMs = builder.readTimeoutMs;
    this.onAuthFailure = builder.onAuthFailure;
  }

  public String getServerUrl() {
    return serverUrl;
  }

  /**
   * Returns the raw API key. Callers MUST NOT log this value at any level. It is exposed here
   * solely for the HTTP transport layer to attach it as a request header.
   */
  @JsonIgnore
  public String getApiKey() {
    return apiKey;
  }

  public int getCacheTtlSeconds() {
    return cacheTtlSeconds;
  }

  public int getMaxStaleSeconds() {
    return maxStaleSeconds;
  }

  public int getConnectTimeoutMs() {
    return connectTimeoutMs;
  }

  public int getReadTimeoutMs() {
    return readTimeoutMs;
  }

  /** Returns the auth-failure hook (SR-02). May be null (no-op). Never logged, never serialised. */
  @JsonIgnore
  public Consumer<InvalidApiKeyException> getOnAuthFailure() {
    return onAuthFailure;
  }

  /**
   * Returns a safe representation for logging. The API key is replaced with {@code [REDACTED]} so
   * this string is safe to pass to any log appender.
   *
   * <p>DO NOT use Lombok {@code @ToString} on this class — it would regenerate this method and
   * include the raw key.
   */
  @Override
  public String toString() {
    return "SdkConfig{"
        + "serverUrl='"
        + serverUrl
        + '\''
        + ", apiKey=[REDACTED]"
        + ", cacheTtlSeconds="
        + cacheTtlSeconds
        + ", maxStaleSeconds="
        + maxStaleSeconds
        + ", connectTimeoutMs="
        + connectTimeoutMs
        + ", readTimeoutMs="
        + readTimeoutMs
        + '}';
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Builder for {@link SdkConfig}. Validates all constraints at {@link #build()} time. */
  public static final class Builder {

    private String serverUrl;
    private String apiKey;
    private int cacheTtlSeconds = 60;
    private int maxStaleSeconds = 0;
    private int connectTimeoutMs = 5000;
    private int readTimeoutMs = 10000;

    /** SR-02: optional auth-failure hook. Null means no-op. */
    private Consumer<InvalidApiKeyException> onAuthFailure = null;

    private Builder() {}

    public Builder serverUrl(String serverUrl) {
      this.serverUrl = serverUrl;
      return this;
    }

    public Builder apiKey(String apiKey) {
      this.apiKey = apiKey;
      return this;
    }

    public Builder cacheTtlSeconds(int cacheTtlSeconds) {
      this.cacheTtlSeconds = cacheTtlSeconds;
      return this;
    }

    public Builder maxStaleSeconds(int maxStaleSeconds) {
      this.maxStaleSeconds = maxStaleSeconds;
      return this;
    }

    public Builder connectTimeoutMs(int connectTimeoutMs) {
      this.connectTimeoutMs = connectTimeoutMs;
      return this;
    }

    public Builder readTimeoutMs(int readTimeoutMs) {
      this.readTimeoutMs = readTimeoutMs;
      return this;
    }

    /**
     * SR-02: registers the auth-failure callback (invoked on HTTP 401 before throw). Null is
     * accepted and treated as a no-op.
     */
    public Builder onAuthFailure(Consumer<InvalidApiKeyException> onAuthFailure) {
      this.onAuthFailure = onAuthFailure;
      return this;
    }

    /**
     * Validates and builds the {@link SdkConfig}.
     *
     * @throws SdkConfigurationException if any constraint is violated
     */
    public SdkConfig build() throws SdkConfigurationException {
      validate();
      return new SdkConfig(this);
    }

    /**
     * Builds the {@link SdkConfig} without scheme validation. <strong>FOR TEST USE ONLY.</strong>
     * This method skips the {@code https://} scheme check so that unit tests may point the client
     * at plain-HTTP loopback servers. Never call from production code — production callers must use
     * {@link #build()}.
     *
     * <p>Access is package-private (SF-4): this path bypasses TLS enforcement and must never be
     * reachable from outside the SDK module. Tests in {@code src/test} are in the same package
     * {@code org.aibles.feature_flag.sdk.internal} or gain access via the outer SDK package test
     * helpers — all of which are within the module's test classpath only.
     *
     * @return a new {@link SdkConfig} with {@code apiKey} and {@code serverUrl} validated for
     *     non-blank only
     */
    SdkConfig buildUnchecked() {
      if (apiKey == null || apiKey.isBlank()) {
        throw new IllegalArgumentException("apiKey must not be blank (buildUnchecked)");
      }
      if (serverUrl == null || serverUrl.isBlank()) {
        throw new IllegalArgumentException("serverUrl must not be blank (buildUnchecked)");
      }
      return new SdkConfig(this);
    }

    private void validate() throws SdkConfigurationException {
      if (serverUrl == null || serverUrl.isBlank()) {
        throw new SdkConfigurationException("serverUrl must not be blank");
      }
      try {
        java.net.URI parsed = new java.net.URI(serverUrl);
        if (!"https".equalsIgnoreCase(parsed.getScheme()) || parsed.getHost() == null) {
          throw new SdkConfigurationException(
              "serverUrl must be a valid https:// URL with a non-blank host"
                  + " — http:// and malformed URLs are not permitted (DE-01 TLS control)");
        }
      } catch (java.net.URISyntaxException e) {
        throw new SdkConfigurationException("serverUrl is not a valid URI: " + e.getMessage(), e);
      }
      if (apiKey == null || apiKey.isBlank()) {
        throw new SdkConfigurationException("apiKey must not be blank");
      }
      if (cacheTtlSeconds < 1 || cacheTtlSeconds > 3600) {
        throw new SdkConfigurationException(
            "cacheTtlSeconds must be between 1 and 3600 (inclusive), got: " + cacheTtlSeconds);
      }
    }
  }
}
