package org.aibles.feature_flag.sdk;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import org.aibles.feature_flag.sdk.exception.SdkConfigurationException;
import org.aibles.feature_flag.sdk.internal.SdkConfig;
import org.aibles.feature_flag.sdk.internal.http.JdkFlagHttpClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * G1 evidence — TLS enforcement tests (DCR-2 / SC-08 / SR-03).
 *
 * <p>Four behavioural assertions:
 *
 * <ol>
 *   <li>Self-signed / untrusted certificate is REJECTED (SSLHandshakeException or wrapping
 *       IOException) — proves cert validation is non-bypassable.
 *   <li>Server offering only TLS 1.1 is REJECTED — proves minimum-version pinning to TLS 1.2+.
 *   <li>{@link FlagClientBuilder} REJECTS an {@code http://} URL at build time with {@link
 *       SdkConfigurationException}.
 *   <li>{@link SdkConfig#toString()} does NOT contain the configured api key literal — proves DCR-1
 *       / SC-01 redaction.
 * </ol>
 *
 * <p>Self-signed KeyStores are generated via {@code keytool} (standard JDK CLI) — no extra test
 * dependencies required.
 */
class TlsEnforcementTest {

  @TempDir Path tempDir;

  // -------------------------------------------------------------------------
  // Test 1: self-signed certificate is rejected (cert validation non-bypassable)
  // -------------------------------------------------------------------------

  @Test
  @DisplayName(
      "self-signed cert causes SSLHandshakeException — cert validation is non-bypassable"
          + " (DCR-2 / SC-08)")
  void selfSignedCertIsRejected() throws Exception {
    KeyStore selfSignedKs = generateSelfSignedKeyStore("test-alias", tempDir.resolve("ks1.jks"));
    int port = startTlsServer(selfSignedKs, /* tls11Only= */ false);

    SdkConfig config =
        SdkConfig.builder()
            .serverUrl("https://localhost:" + port)
            .apiKey("syn-test-key-0000000000000000000000000000000000000000000000000000000000")
            .cacheTtlSeconds(60)
            .connectTimeoutMs(3000)
            .readTimeoutMs(3000)
            .build();
    JdkFlagHttpClient client = new JdkFlagHttpClient(config);

    IOException thrown =
        assertThrows(
            IOException.class,
            () -> client.fetchOne("checkout-v2", null),
            "Expected SSL handshake failure for untrusted self-signed certificate");

    assertTrue(
        isSslRelated(thrown),
        "Exception must be SSL-related (SSLHandshakeException or wrapping cause): "
            + thrown.getClass().getName()
            + ": "
            + thrown.getMessage());
  }

  // -------------------------------------------------------------------------
  // Test 2: TLS 1.1-only server is rejected (minimum-version pinned to TLS 1.2+)
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("TLS 1.1-only server is rejected — minimum-version pinned to TLS 1.2+ (SR-03)")
  void tls11ServerIsRejected() throws Exception {
    KeyStore tls11Ks = generateSelfSignedKeyStore("tls11-alias", tempDir.resolve("ks2.jks"));
    int port = startTlsServer(tls11Ks, /* tls11Only= */ true);

    SdkConfig config =
        SdkConfig.builder()
            .serverUrl("https://localhost:" + port)
            .apiKey("syn-test-key-0000000000000000000000000000000000000000000000000000000000")
            .cacheTtlSeconds(60)
            .connectTimeoutMs(3000)
            .readTimeoutMs(3000)
            .build();
    JdkFlagHttpClient client = new JdkFlagHttpClient(config);

    // H-1 NON-VACUOUS PROOF: assert the SDK's own SSLParameters pinning is in place.
    // If the sslParameters.setProtocols() call is removed from buildHttpClient(), this
    // assertion fails (returns an empty array), proving the test is not relying on JDK defaults.
    String[] protocols = client.configuredProtocols();
    assertArrayEquals(
        new String[] {"TLSv1.3", "TLSv1.2"},
        protocols,
        "JdkFlagHttpClient must pin exactly [TLSv1.3, TLSv1.2] — removing setProtocols() fails here");

    // BEHAVIOURAL ASSERTION: SSLHandshakeException (or an IOException wrapping it): SDK refuses
    // TLS 1.1. This assertion is kept alongside the configuration proof above.
    assertThrows(
        IOException.class,
        () -> client.fetchOne("checkout-v2", null),
        "Expected IOException (wrapping SSL error) when server is constrained to TLS 1.1 only");
  }

  // -------------------------------------------------------------------------
  // Test 3: http:// URL is rejected at build time
  // -------------------------------------------------------------------------

  @Test
  @DisplayName(
      "FlagClientBuilder rejects http:// URL with SdkConfigurationException (SC-02 / DE-01)")
  void httpUrlIsRejectedAtBuildTime() {
    assertThrows(
        SdkConfigurationException.class,
        () ->
            new FlagClientBuilder()
                .serverUrl("http://flags.internal/api/v1")
                .apiKey("syn-test-key-0000000000000000000000000000000000000000000000000000000000")
                .cacheTtlSeconds(60)
                .build(),
        "Expected SdkConfigurationException for http:// URL");
  }

  // -------------------------------------------------------------------------
  // Test 4: SdkConfig.toString() never contains the configured API-key literal
  // -------------------------------------------------------------------------

  @Test
  @DisplayName(
      "SdkConfig.toString() does not contain the configured api key — redaction SC-01 / DCR-1")
  void sdkConfigToStringRedactsApiKey() throws SdkConfigurationException {
    String syntheticKey =
        "syn-secret-key-abcdef1234567890abcdef1234567890abcdef1234567890abcdef12345678";
    SdkConfig config =
        SdkConfig.builder()
            .serverUrl("https://flags.internal")
            .apiKey(syntheticKey)
            .cacheTtlSeconds(60)
            .connectTimeoutMs(5000)
            .readTimeoutMs(5000)
            .build();

    String rendered = config.toString();

    assertFalse(
        rendered.contains(syntheticKey),
        "SdkConfig.toString() must NOT contain the raw api key literal. Got: " + rendered);
    assertTrue(
        rendered.contains("[REDACTED]"),
        "SdkConfig.toString() must contain '[REDACTED]' placeholder. Got: " + rendered);
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  /**
   * Generates a fresh self-signed KeyStore by invoking {@code keytool} (standard JDK utility). The
   * certificate is NOT registered in the JVM trust store and will be rejected by the default
   * SSLContext. Uses synthetic identifiers only — no real PII.
   */
  private static KeyStore generateSelfSignedKeyStore(String alias, Path ksPath) throws Exception {
    String[] keytoolCmd = {
      "keytool",
      "-genkeypair",
      "-keyalg",
      "RSA",
      "-keysize",
      "2048",
      "-validity",
      "1",
      "-alias",
      alias,
      "-dname",
      "CN=sdk-tls-test,O=Synthetic,C=VN",
      "-keystore",
      ksPath.toAbsolutePath().toString(),
      "-storepass",
      "changeit",
      "-keypass",
      "changeit",
      "-storetype",
      "JKS",
      "-noprompt"
    };

    Process proc = new ProcessBuilder(keytoolCmd).redirectErrorStream(true).start();
    int exit = proc.waitFor();
    if (exit != 0) {
      String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      throw new IllegalStateException("keytool failed (exit " + exit + "): " + out);
    }

    KeyStore ks = KeyStore.getInstance("JKS");
    try (var in = Files.newInputStream(ksPath)) {
      ks.load(in, "changeit".toCharArray());
    }
    return ks;
  }

  /**
   * Starts a lightweight {@link SSLServerSocket} on a free ephemeral port. Accepts one connection
   * then closes. If {@code tls11Only} is true the socket is constrained to TLSv1 / TLSv1.1,
   * simulating a downgrade server that the SDK's TLS 1.2+ pinning should reject.
   *
   * @return the bound port number
   */
  private static int startTlsServer(KeyStore keyStore, boolean tls11Only) throws Exception {
    KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    kmf.init(keyStore, "changeit".toCharArray());

    SSLContext sslCtx = SSLContext.getInstance("TLS");
    sslCtx.init(kmf.getKeyManagers(), null, new SecureRandom());

    SSLServerSocketFactory ssf = sslCtx.getServerSocketFactory();
    SSLServerSocket serverSocket = (SSLServerSocket) ssf.createServerSocket(0);

    if (tls11Only) {
      // Restrict server to TLS 1.0 / 1.1 only — client pinned to TLS 1.2+ must refuse.
      serverSocket.setEnabledProtocols(new String[] {"TLSv1", "TLSv1.1"});
    }

    int boundPort = serverSocket.getLocalPort();

    Thread serverThread =
        new Thread(
            () -> {
              try {
                var conn = serverSocket.accept();
                OutputStream out = conn.getOutputStream();
                out.write(
                    "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}"
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
                conn.close();
              } catch (IOException ignored) {
                // Expected on handshake rejection.
              } finally {
                try {
                  serverSocket.close();
                } catch (IOException ignored) {
                }
              }
            },
            "tls-test-server-" + boundPort);
    serverThread.setDaemon(true);
    serverThread.start();

    return boundPort;
  }

  /** Returns true if the throwable or any cause in its chain is SSL-related. */
  private static boolean isSslRelated(Throwable t) {
    while (t != null) {
      if (t instanceof SSLHandshakeException) return true;
      if (t.getClass().getName().contains("SSL")) return true;
      t = t.getCause();
    }
    return false;
  }
}
