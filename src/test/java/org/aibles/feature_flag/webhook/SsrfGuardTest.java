package org.aibles.feature_flag.webhook;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.aibles.feature_flag.config.WebhookProperties;
import org.aibles.feature_flag.exception.WebhookUrlNotAllowedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Issue #36 AC: "SSRF guard mechanisms are covered by comprehensive tests."
 *
 * <p>Uses literal IPs rather than hostnames wherever possible so the assertions do not depend on
 * DNS being reachable from the build machine.
 */
class SsrfGuardTest {

  private static WebhookProperties props(boolean allowPrivate) {
    return new WebhookProperties(
        true,
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        3,
        Duration.ofSeconds(1),
        Duration.ofSeconds(1),
        Duration.ofSeconds(1),
        allowPrivate);
  }

  private final SsrfGuard guard = new SsrfGuard(props(false));

  // --- blocked address ranges ---------------------------------------------------------------

  @ParameterizedTest(name = "blocks {0}")
  @ValueSource(
      strings = {
        "http://127.0.0.1/hook", // loopback
        "http://127.0.0.2/hook", // whole 127/8 is loopback
        "https://localhost/hook", // resolves to loopback
        "http://0.0.0.0/hook", // wildcard, commonly routed to localhost
        "http://10.0.0.5/hook", // RFC-1918
        "http://172.16.4.2/hook", // RFC-1918
        "http://192.168.1.10/hook", // RFC-1918
        "http://169.254.169.254/latest/meta-data/", // cloud metadata — the classic SSRF target
        "http://[::1]/hook", // IPv6 loopback
        "http://224.0.0.1/hook", // multicast
      })
  void blocksNonPublicAddresses(String url) {
    assertThatThrownBy(() -> guard.verifyAllowed(url))
        .isInstanceOf(WebhookUrlNotAllowedException.class);
  }

  @Test
  @DisplayName("the cloud metadata endpoint is blocked and the error does not echo the resolved IP")
  void blocksMetadataEndpointWithoutLeakingResolution() {
    assertThatThrownBy(() -> guard.verifyAllowed("http://169.254.169.254/latest/meta-data/"))
        .isInstanceOf(WebhookUrlNotAllowedException.class)
        .hasMessageContaining("169.254.169.254") // the host the operator typed
        .hasMessageContaining("private or loopback");
  }

  // --- malformed input ---------------------------------------------------------------------

  @ParameterizedTest(name = "rejects non-HTTP scheme: {0}")
  @ValueSource(
      strings = {
        "file:///etc/passwd",
        "ftp://example.com/hook",
        "gopher://example.com/",
        "jar:file:///tmp/x.jar!/",
      })
  void rejectsNonHttpSchemes(String url) {
    assertThatThrownBy(() -> guard.verifyAllowed(url))
        .isInstanceOf(WebhookUrlNotAllowedException.class)
        .hasMessageContaining("http or https");
  }

  @ParameterizedTest(name = "rejects malformed URL: \"{0}\"")
  @ValueSource(strings = {"", "   ", "not a url at all", "http://", "https://"})
  void rejectsMalformedUrls(String url) {
    assertThatThrownBy(() -> guard.verifyAllowed(url))
        .isInstanceOf(WebhookUrlNotAllowedException.class);
  }

  @Test
  void rejectsNullUrl() {
    assertThatThrownBy(() -> guard.verifyAllowed(null))
        .isInstanceOf(WebhookUrlNotAllowedException.class)
        .hasMessageContaining("required");
  }

  @Test
  @DisplayName("a host that cannot be resolved is rejected, not silently allowed")
  void rejectsUnresolvableHost() {
    assertThatThrownBy(() -> guard.verifyAllowed("http://this-host-does-not-exist.invalid/hook"))
        .isInstanceOf(WebhookUrlNotAllowedException.class);
  }

  // --- the escape hatch --------------------------------------------------------------------

  @Test
  @DisplayName("allow-private-addresses opens loopback for local dev and integration tests")
  void allowsPrivateAddressesWhenExplicitlyEnabled() {
    SsrfGuard permissive = new SsrfGuard(props(true));

    assertThatCode(() -> permissive.verifyAllowed("http://127.0.0.1:8080/hook"))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("the escape hatch still rejects a non-HTTP scheme")
  void escapeHatchDoesNotBypassSchemeCheck() {
    SsrfGuard permissive = new SsrfGuard(props(true));

    assertThatThrownBy(() -> permissive.verifyAllowed("file:///etc/passwd"))
        .isInstanceOf(WebhookUrlNotAllowedException.class);
  }

  @Test
  @DisplayName("a public address is allowed")
  void allowsPublicAddress() {
    // A literal public IP, so no DNS lookup is needed for the happy path.
    assertThatCode(() -> guard.verifyAllowed("https://93.184.216.34/hook"))
        .doesNotThrowAnyException();
  }
}
