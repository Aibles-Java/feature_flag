package org.aibles.feature_flag.webhook;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.aibles.feature_flag.config.WebhookProperties;
import org.springframework.stereotype.Component;

/**
 * Blocks webhook deliveries that would reach the platform's own network (issue #36).
 *
 * <p>A webhook URL is attacker-controlled input that the <em>server</em> then requests, which is
 * the definition of SSRF. Without a guard, a tenant could subscribe to {@code
 * http://169.254.169.254/latest/meta-data/} and have the platform fetch cloud instance credentials
 * for them, or probe internal services by observing delivery status codes.
 *
 * <p>The URL is validated at <strong>two</strong> points, deliberately:
 *
 * <ol>
 *   <li><em>Subscribe time</em> — so an operator gets an immediate 400 rather than a silently dead
 *       subscription.
 *   <li><em>Delivery time</em> — because DNS is mutable. A hostname that resolved to a public
 *       address when subscribed can later resolve to {@code 127.0.0.1} (DNS rebinding), so a
 *       subscribe-time-only check is bypassable by design.
 * </ol>
 *
 * <p>Residual risk worth knowing: even a delivery-time check has a TOCTOU window, because the HTTP
 * client re-resolves the name when it connects. Fully closing it requires pinning the connection to
 * the validated IP; that is out of scope here and recorded in ADR-0005.
 */
@Component
@RequiredArgsConstructor
public class SsrfGuard {

  private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

  private final WebhookProperties properties;

  /**
   * @throws WebhookUrlNotAllowedException if the URL is malformed, uses a non-HTTP scheme, or
   *     resolves to any non-public address
   */
  public void verifyAllowed(String url) {
    URI uri = parse(url);

    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!ALLOWED_SCHEMES.contains(scheme)) {
      throw new WebhookUrlNotAllowedException(
          "webhook URL must use http or https, got: "
              + (uri.getScheme() == null ? "none" : scheme));
    }

    String host = uri.getHost();
    if (host == null || host.isBlank()) {
      throw new WebhookUrlNotAllowedException("webhook URL has no host");
    }

    if (properties.allowPrivateAddresses()) {
      // Escape hatch for local development and integration tests, which must POST to
      // 127.0.0.1. Defaults to false so production is guarded unless explicitly opened.
      return;
    }

    for (InetAddress address : resolve(host)) {
      if (isPrivate(address)) {
        // Report the host, never the resolved address: echoing back what an internal name
        // resolves to is itself an information leak.
        throw new WebhookUrlNotAllowedException(
            "webhook URL resolves to a private or loopback address: " + host);
      }
    }
  }

  private static URI parse(String url) {
    if (url == null || url.isBlank()) {
      throw new WebhookUrlNotAllowedException("webhook URL is required");
    }
    try {
      return new URI(url);
    } catch (URISyntaxException e) {
      throw new WebhookUrlNotAllowedException("webhook URL is not a valid URI");
    }
  }

  /** Resolves every address for the host — a name can map to both a public and a private IP. */
  private static InetAddress[] resolve(String host) {
    try {
      return InetAddress.getAllByName(host);
    } catch (UnknownHostException e) {
      throw new WebhookUrlNotAllowedException("webhook URL host cannot be resolved: " + host);
    }
  }

  /**
   * Anything that is not a routable public address.
   *
   * <p>{@code isSiteLocalAddress} covers RFC-1918 (10/8, 172.16/12, 192.168/16); {@code
   * isLinkLocalAddress} covers 169.254/16, which is where the cloud metadata endpoint lives; {@code
   * isAnyLocalAddress} covers 0.0.0.0, which many stacks route to localhost.
   */
  private static boolean isPrivate(InetAddress address) {
    return address.isLoopbackAddress()
        || address.isLinkLocalAddress()
        || address.isSiteLocalAddress()
        || address.isAnyLocalAddress()
        || address.isMulticastAddress();
  }
}
