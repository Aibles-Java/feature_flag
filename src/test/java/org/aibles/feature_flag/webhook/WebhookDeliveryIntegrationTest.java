package org.aibles.feature_flag.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.domain.entity.Organization;
import org.aibles.feature_flag.domain.entity.Project;
import org.aibles.feature_flag.domain.entity.WebhookDeliveryAttempt;
import org.aibles.feature_flag.domain.entity.WebhookSubscription;
import org.aibles.feature_flag.domain.enums.WebhookEventType;
import org.aibles.feature_flag.notification.event.FlagStateChangedEvent;
import org.aibles.feature_flag.repository.EnvironmentRepository;
import org.aibles.feature_flag.repository.OrganizationRepository;
import org.aibles.feature_flag.repository.ProjectRepository;
import org.aibles.feature_flag.repository.WebhookDeliveryAttemptRepository;
import org.aibles.feature_flag.repository.WebhookSubscriptionRepository;
import org.aibles.feature_flag.util.ApiKeyHasher;
import org.aibles.feature_flag.util.SecretCipher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Issue #36 AC: "flag-state changes trigger signed POST requests to subscribed URLs (verified via
 * integration testing)" and "failed deliveries are retried with exponential backoff and tracked".
 *
 * <p>Runs against a real JDK {@link HttpServer} on loopback and the real H2 database, so the whole
 * pipeline is exercised: after-commit event → async dispatcher → HMAC signing → HTTP POST →
 * delivery attempt persisted.
 *
 * <p>Two things this test has to get right, both previously-recorded traps:
 *
 * <ul>
 *   <li>The test method must <strong>not</strong> be {@code @Transactional}. The dispatcher listens
 *       on {@code AFTER_COMMIT}, so a rolled-back test transaction would never fire it and the test
 *       would pass while delivering nothing.
 *   <li>Distinct {@code @SpringBootTest} properties fork a second application context, so this gets
 *       its own H2 database — otherwise Liquibase re-runs on the shared {@code testdb} and fails
 *       with "DATABASECHANGELOG already exists".
 * </ul>
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:webhook-testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
      "app.webhook.enabled=true",
      // The receiver runs on 127.0.0.1, which the SSRF guard blocks by default.
      "app.webhook.allow-private-addresses=true",
      "app.webhook.max-attempts=3",
      // Keep the test fast; the backoff doubling is asserted on attempt count, not wall-clock.
      "app.webhook.initial-backoff=20ms"
    })
@ActiveProfiles("test")
class WebhookDeliveryIntegrationTest {

  private static final String SECRET = "whsec_integration_test_secret";

  @Autowired ApplicationEventPublisher eventPublisher;
  @Autowired TransactionTemplate transactionTemplate;
  @Autowired OrganizationRepository organizationRepository;
  @Autowired ProjectRepository projectRepository;
  @Autowired EnvironmentRepository environmentRepository;
  @Autowired WebhookSubscriptionRepository subscriptionRepository;
  @Autowired WebhookDeliveryAttemptRepository attemptRepository;
  @Autowired SecretCipher secretCipher;
  @Autowired WebhookSigner signer;

  private HttpServer server;
  private final List<ReceivedRequest> received = new CopyOnWriteArrayList<>();
  private final AtomicInteger responseStatus = new AtomicInteger(200);

  private Environment environment;

  record ReceivedRequest(String body, String signature, String timestamp, String event) {}

  @BeforeEach
  void setUp() throws IOException {
    received.clear();
    responseStatus.set(200);

    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext("/hook", this::handle);
    server.start();

    Organization org =
        organizationRepository.save(
            Organization.builder().name("Acme").slug("acme-" + UUID.randomUUID()).build());
    Project project =
        projectRepository.save(Project.builder().organization(org).name("web").build());
    environment =
        environmentRepository.save(
            Environment.builder()
                .project(project)
                .name("production")
                .apiKeyHash(ApiKeyHasher.hash(UUID.randomUUID().toString()))
                .build());
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  private void handle(HttpExchange exchange) throws IOException {
    String body;
    try (InputStream in = exchange.getRequestBody()) {
      body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    received.add(
        new ReceivedRequest(
            body,
            exchange.getRequestHeaders().getFirst(WebhookSigner.SIGNATURE_HEADER),
            exchange.getRequestHeaders().getFirst(WebhookSigner.TIMESTAMP_HEADER),
            exchange.getRequestHeaders().getFirst(WebhookSigner.EVENT_HEADER)));
    exchange.sendResponseHeaders(responseStatus.get(), -1);
    exchange.close();
  }

  private String hookUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
  }

  private WebhookSubscription subscribe(WebhookEventType... eventTypes) {
    return subscriptionRepository.save(
        WebhookSubscription.builder()
            .environmentId(environment.getId())
            .url(hookUrl())
            .secretCiphertext(secretCipher.encrypt(SECRET))
            .eventTypes(EnumSet.copyOf(List.of(eventTypes)))
            .enabled(true)
            .build());
  }

  /** Publishes inside a real committed transaction so the AFTER_COMMIT listener actually fires. */
  private void publishCommitted(FlagStateChangedEvent event) {
    transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(event));
  }

  private FlagStateChangedEvent stateChange() {
    return new FlagStateChangedEvent(
        environment.getId(),
        "checkout-v2",
        "production",
        "web",
        false,
        true,
        "off",
        "on",
        "dev@example.com");
  }

  @Test
  @DisplayName("a flag-state change delivers a POST whose signature verifies with the secret")
  void deliversSignedPost() {
    WebhookSubscription subscription = subscribe(WebhookEventType.FLAG_STATE_CHANGED);

    publishCommitted(stateChange());

    await().atMost(Duration.ofSeconds(10)).until(() -> !received.isEmpty());
    ReceivedRequest request = received.get(0);

    assertThat(request.event()).isEqualTo("FLAG_STATE_CHANGED");
    assertThat(request.body()).contains("checkout-v2").contains("FLAG_STATE_CHANGED");
    assertThat(request.body()).as("the payload must never carry a secret").doesNotContain(SECRET);

    // The AC: verifiable using the shared secret.
    long timestamp = Long.parseLong(request.timestamp());
    assertThat(signer.verify(SECRET, timestamp, request.body(), request.signature()))
        .as("signature must verify over the exact transmitted body")
        .isTrue();
    assertThat(signer.verify("wrong-secret", timestamp, request.body(), request.signature()))
        .isFalse();

    await().atMost(Duration.ofSeconds(5)).until(() -> attemptsFor(subscription).size() == 1);
    assertThat(attemptsFor(subscription).get(0).isSucceeded()).isTrue();
  }

  @Test
  @DisplayName("a failing endpoint is retried up to max-attempts and every attempt is recorded")
  void retriesAndTracksFailures() {
    responseStatus.set(500);
    WebhookSubscription subscription = subscribe(WebhookEventType.FLAG_STATE_CHANGED);

    publishCommitted(stateChange());

    // 3 attempts with 20ms/40ms backoff between them.
    await().atMost(Duration.ofSeconds(15)).until(() -> received.size() == 3);

    await().atMost(Duration.ofSeconds(5)).until(() -> attemptsFor(subscription).size() == 3);

    List<WebhookDeliveryAttempt> attempts = attemptsFor(subscription);
    assertThat(attempts).allMatch(a -> !a.isSucceeded());
    assertThat(attempts).allMatch(a -> a.getResponseStatus() == 500);
    assertThat(attempts).extracting("attempt").containsExactlyInAnyOrder(1, 2, 3);
  }

  @Test
  @DisplayName("a subscription not listening for the event receives nothing")
  void doesNotDeliverUnsubscribedEventTypes() throws InterruptedException {
    subscribe(WebhookEventType.API_KEY_ROTATED); // not FLAG_STATE_CHANGED

    publishCommitted(stateChange());

    // Nothing to await on, so give the async path a real chance to prove it stays quiet.
    Thread.sleep(500);
    assertThat(received).isEmpty();
  }

  @Test
  @DisplayName("a disabled subscription receives nothing")
  void doesNotDeliverToDisabledSubscription() throws InterruptedException {
    WebhookSubscription subscription = subscribe(WebhookEventType.FLAG_STATE_CHANGED);
    subscription.setEnabled(false);
    subscriptionRepository.save(subscription);

    publishCommitted(stateChange());

    Thread.sleep(500);
    assertThat(received).isEmpty();
  }

  private List<WebhookDeliveryAttempt> attemptsFor(WebhookSubscription subscription) {
    return attemptRepository
        .findAllBySubscriptionId(subscription.getId(), PageRequest.of(0, 10))
        .getContent();
  }
}
