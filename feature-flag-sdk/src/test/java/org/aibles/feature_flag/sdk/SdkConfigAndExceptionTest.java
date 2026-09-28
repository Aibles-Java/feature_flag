package org.aibles.feature_flag.sdk;

import static org.junit.jupiter.api.Assertions.*;

import org.aibles.feature_flag.sdk.exception.FlagTypeMismatchException;
import org.aibles.feature_flag.sdk.exception.InvalidApiKeyException;
import org.aibles.feature_flag.sdk.exception.SdkConfigurationException;
import org.aibles.feature_flag.sdk.exception.SdkException;
import org.aibles.feature_flag.sdk.internal.SdkConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for SdkConfig, FlagClientBuilder validation, exception hierarchy, and FlagClient
 * construction. Covers lines left uncovered by TlsEnforcementTest (JaCoCo 80% floor).
 */
class SdkConfigAndExceptionTest {

  // -------------------------------------------------------------------------
  // SdkConfig — field accessors and redaction
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("SdkConfig accessors return values set by builder")
  void sdkConfigAccessorsReturnBuiltValues() throws SdkConfigurationException {
    SdkConfig cfg =
        SdkConfig.builder()
            .serverUrl("https://flags.example.com")
            .apiKey("syn-key-12345678901234567890123456789012345678901234567890123456789012")
            .cacheTtlSeconds(120)
            .maxStaleSeconds(300)
            .connectTimeoutMs(2000)
            .readTimeoutMs(8000)
            .build();

    assertEquals("https://flags.example.com", cfg.getServerUrl());
    assertEquals(120, cfg.getCacheTtlSeconds());
    assertEquals(300, cfg.getMaxStaleSeconds());
    assertEquals(2000, cfg.getConnectTimeoutMs());
    assertEquals(8000, cfg.getReadTimeoutMs());
    // getApiKey() is exposed for the HTTP layer — just check it is non-blank.
    assertFalse(cfg.getApiKey().isBlank());
  }

  @Test
  @DisplayName("SdkConfig.builder() rejects blank serverUrl")
  void sdkConfigRejectsBlankServerUrl() {
    assertThrows(
        SdkConfigurationException.class,
        () ->
            SdkConfig.builder()
                .serverUrl("")
                .apiKey("syn-key-12345678901234567890123456789012345678901234567890123456789012")
                .cacheTtlSeconds(60)
                .build());
  }

  @Test
  @DisplayName("SdkConfig.builder() rejects blank apiKey")
  void sdkConfigRejectsBlankApiKey() {
    assertThrows(
        SdkConfigurationException.class,
        () ->
            SdkConfig.builder()
                .serverUrl("https://flags.example.com")
                .apiKey("")
                .cacheTtlSeconds(60)
                .build());
  }

  @Test
  @DisplayName("SdkConfig.builder() rejects cacheTtlSeconds=0 (below minimum)")
  void sdkConfigRejectsTtlBelowMinimum() {
    assertThrows(
        SdkConfigurationException.class,
        () ->
            SdkConfig.builder()
                .serverUrl("https://flags.example.com")
                .apiKey("syn-key-12345678901234567890123456789012345678901234567890123456789012")
                .cacheTtlSeconds(0)
                .build());
  }

  @Test
  @DisplayName("SdkConfig.builder() rejects cacheTtlSeconds=3601 (above maximum)")
  void sdkConfigRejectsTtlAboveMaximum() {
    assertThrows(
        SdkConfigurationException.class,
        () ->
            SdkConfig.builder()
                .serverUrl("https://flags.example.com")
                .apiKey("syn-key-12345678901234567890123456789012345678901234567890123456789012")
                .cacheTtlSeconds(3601)
                .build());
  }

  @Test
  @DisplayName("SdkConfig.builder() rejects a syntactically invalid URI")
  void sdkConfigRejectsMalformedUri() {
    assertThrows(
        SdkConfigurationException.class,
        () ->
            SdkConfig.builder()
                .serverUrl("not a valid :// uri")
                .apiKey("syn-key-12345678901234567890123456789012345678901234567890123456789012")
                .cacheTtlSeconds(60)
                .build());
  }

  // -------------------------------------------------------------------------
  // SdkConfigurationException — checked-exception constructors
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("SdkConfigurationException(String) stores the message")
  void sdkConfigurationExceptionMessageOnly() {
    var ex = new SdkConfigurationException("bad config");
    assertEquals("bad config", ex.getMessage());
    assertNull(ex.getCause());
  }

  @Test
  @DisplayName("SdkConfigurationException(String, Throwable) stores message and cause")
  void sdkConfigurationExceptionWithCause() {
    var cause = new RuntimeException("root cause");
    var ex = new SdkConfigurationException("bad config", cause);
    assertEquals("bad config", ex.getMessage());
    assertSame(cause, ex.getCause());
  }

  // -------------------------------------------------------------------------
  // SdkException hierarchy
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("SdkException(String) is constructible and message is accessible")
  void sdkExceptionMessageOnly() {
    SdkException ex = new SdkException("sdk error");
    assertEquals("sdk error", ex.getMessage());
    assertNull(ex.getCause());
    assertInstanceOf(RuntimeException.class, ex);
  }

  @Test
  @DisplayName("SdkException(String, Throwable) stores message and cause")
  void sdkExceptionWithCause() {
    var cause = new IllegalStateException("root");
    SdkException ex = new SdkException("sdk error", cause);
    assertEquals("sdk error", ex.getMessage());
    assertSame(cause, ex.getCause());
  }

  @Test
  @DisplayName("InvalidApiKeyException is an SdkException with the correct message")
  void invalidApiKeyExceptionIsSubtype() {
    var ex = new InvalidApiKeyException("API key rejected — key may have been rotated");
    assertInstanceOf(SdkException.class, ex);
    assertEquals("API key rejected — key may have been rotated", ex.getMessage());
  }

  @Test
  @DisplayName("InvalidApiKeyException(String, Throwable) stores cause")
  void invalidApiKeyExceptionWithCause() {
    var cause = new RuntimeException("transport error");
    var ex = new InvalidApiKeyException("msg", cause);
    assertSame(cause, ex.getCause());
  }

  @Test
  @DisplayName("FlagTypeMismatchException exposes flagKey and a human-readable message")
  void flagTypeMismatchExceptionFields() {
    var ex = new FlagTypeMismatchException("my-flag", "BOOLEAN", "STRING");
    assertInstanceOf(SdkException.class, ex);
    assertEquals("my-flag", ex.getFlagKey());
    String msg = ex.getMessage();
    assertTrue(msg.contains("my-flag"), "Message should contain the flag key");
    assertTrue(msg.contains("BOOLEAN"), "Message should contain the expected type");
    assertTrue(msg.contains("STRING"), "Message should contain the actual type");
  }

  // -------------------------------------------------------------------------
  // FlagClientBuilder — happy path + validation edge cases
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("FlagClientBuilder.build() succeeds for a valid https:// URL")
  void flagClientBuilderSucceedsForValidConfig() throws SdkConfigurationException {
    FlagClient client =
        new FlagClientBuilder()
            .serverUrl("https://flags.example.com")
            .apiKey("syn-key-12345678901234567890123456789012345678901234567890123456789012")
            .cacheTtlSeconds(60)
            .build();
    assertNotNull(client);
    client.close();
  }

  @Test
  @DisplayName("FlagClientBuilder.build() rejects null serverUrl")
  void flagClientBuilderRejectsNullServerUrl() {
    assertThrows(
        SdkConfigurationException.class,
        () ->
            new FlagClientBuilder()
                .apiKey("syn-key-12345678901234567890123456789012345678901234567890123456789012")
                .cacheTtlSeconds(60)
                .build());
  }

  @Test
  @DisplayName("FlagClientBuilder honours custom timeout values")
  void flagClientBuilderHonoursCustomTimeouts() throws SdkConfigurationException {
    // Builds without throwing — validates timeout fields are plumbed through.
    try (FlagClient client =
        new FlagClientBuilder()
            .serverUrl("https://flags.example.com")
            .apiKey("syn-key-12345678901234567890123456789012345678901234567890123456789012")
            .cacheTtlSeconds(30)
            .maxStaleSeconds(120)
            .connectTimeoutMs(1500)
            .readTimeoutMs(6000)
            .build()) {
      assertNotNull(client);
    }
  }

  // -------------------------------------------------------------------------
  // FlagClient
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("FlagClient.close() is idempotent and does not throw")
  void flagClientCloseIsIdempotent() throws SdkConfigurationException {
    FlagClient client =
        new FlagClientBuilder()
            .serverUrl("https://flags.example.com")
            .apiKey("syn-key-12345678901234567890123456789012345678901234567890123456789012")
            .cacheTtlSeconds(60)
            .build();
    assertDoesNotThrow(client::close);
    assertDoesNotThrow(client::close);
  }
}
