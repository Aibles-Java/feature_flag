package org.aibles.feature_flag.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import org.aibles.feature_flag.config.WebhookConfig;
import org.aibles.feature_flag.config.WebhookProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Guards a load-bearing security property that is <em>invisible in our own code</em>.
 *
 * <p>{@link SsrfGuard} validates the subscription URL, but it cannot see where a redirect would
 * take the request. If the delivery client followed a {@code 302}, a subscriber could register a
 * perfectly public URL that redirects to {@code http://169.254.169.254/} and read cloud metadata
 * through us — the guard would be fully bypassed.
 *
 * <p>Today that cannot happen, because Spring's {@code SimpleClientHttpRequestFactory} enables
 * {@code setInstanceFollowRedirects} for {@code GET} only, and deliveries are {@code POST}. That is
 * an implementation detail of the configured request factory, not something our code states — so
 * swapping the factory (to the JDK or Apache client, both of which follow redirects on POST for
 * some status codes) would silently reopen the hole. This test fails if that ever happens.
 */
class WebhookRedirectNotFollowedTest {

  private HttpServer server;
  private final AtomicBoolean redirectTargetHit = new AtomicBoolean(false);

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    int port = server.getAddress().getPort();
    server.createContext(
        "/redirect",
        exchange -> {
          drain(exchange.getRequestBody());
          exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + port + "/private");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    server.createContext(
        "/private",
        exchange -> {
          redirectTargetHit.set(true);
          drain(exchange.getRequestBody());
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  private static void drain(InputStream in) throws IOException {
    try (InputStream body = in) {
      body.readAllBytes();
    }
  }

  @Test
  @DisplayName("a POST delivery does not follow a redirect, so SsrfGuard cannot be bypassed by one")
  void doesNotFollowRedirects() {
    RestClient client =
        new WebhookConfig()
            .webhookRestClient(
                new WebhookProperties(
                    true,
                    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                    3,
                    null,
                    null,
                    null,
                    true));

    try {
      client
          .post()
          .uri("http://127.0.0.1:" + server.getAddress().getPort() + "/redirect")
          .contentType(MediaType.APPLICATION_JSON)
          .body("{}")
          .retrieve()
          .toBodilessEntity();
    } catch (RuntimeException e) {
      // A 3xx surfaces as an error to the caller; either way what matters is the assertion below.
    }

    assertThat(redirectTargetHit)
        .as("the redirect target must never be requested by a webhook delivery")
        .isFalse();
  }
}
