package org.aibles.feature_flag.sdk;

import org.aibles.feature_flag.sdk.exception.SdkConfigurationException;
import org.aibles.feature_flag.sdk.internal.SdkConfig;

/**
 * Builder for the Feature Flag SDK client.
 *
 * <p>Validation rules (LLD §6.2):
 *
 * <ul>
 *   <li>{@code serverUrl} must be a valid {@code https://} URL with a non-blank host — {@code
 *       http://} and malformed URLs are rejected at build time.
 *   <li>{@code apiKey} must not be blank.
 *   <li>{@code cacheTtlSeconds} must be in range [1, 3600].
 * </ul>
 *
 * <p>All violations throw {@link SdkConfigurationException} (checked) from {@link #build()}.
 */
public final class FlagClientBuilder {

  private String serverUrl;
  private String apiKey;
  private int cacheTtlSeconds = 60;
  private int maxStaleSeconds = 0;
  private int connectTimeoutMs = 5000;
  private int readTimeoutMs = 10000;

  public FlagClientBuilder serverUrl(String serverUrl) {
    this.serverUrl = serverUrl;
    return this;
  }

  public FlagClientBuilder apiKey(String apiKey) {
    this.apiKey = apiKey;
    return this;
  }

  public FlagClientBuilder cacheTtlSeconds(int cacheTtlSeconds) {
    this.cacheTtlSeconds = cacheTtlSeconds;
    return this;
  }

  public FlagClientBuilder maxStaleSeconds(int maxStaleSeconds) {
    this.maxStaleSeconds = maxStaleSeconds;
    return this;
  }

  public FlagClientBuilder connectTimeoutMs(int connectTimeoutMs) {
    this.connectTimeoutMs = connectTimeoutMs;
    return this;
  }

  public FlagClientBuilder readTimeoutMs(int readTimeoutMs) {
    this.readTimeoutMs = readTimeoutMs;
    return this;
  }

  /**
   * Validates configuration and builds the {@link FlagClient}.
   *
   * @throws SdkConfigurationException if any configuration constraint is violated
   */
  public FlagClient build() throws SdkConfigurationException {
    SdkConfig config =
        SdkConfig.builder()
            .serverUrl(serverUrl)
            .apiKey(apiKey)
            .cacheTtlSeconds(cacheTtlSeconds)
            .maxStaleSeconds(maxStaleSeconds)
            .connectTimeoutMs(connectTimeoutMs)
            .readTimeoutMs(readTimeoutMs)
            .build();
    return new FlagClient(config);
  }
}
