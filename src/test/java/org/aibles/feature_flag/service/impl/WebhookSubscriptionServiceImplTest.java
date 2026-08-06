package org.aibles.feature_flag.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.aibles.feature_flag.domain.entity.WebhookSubscription;
import org.aibles.feature_flag.domain.enums.MemberRole;
import org.aibles.feature_flag.domain.enums.WebhookEventType;
import org.aibles.feature_flag.dto.request.CreateWebhookSubscriptionRequest;
import org.aibles.feature_flag.dto.request.UpdateWebhookSubscriptionRequest;
import org.aibles.feature_flag.dto.response.WebhookSubscriptionResponse;
import org.aibles.feature_flag.dto.response.WebhookSubscriptionSecretResponse;
import org.aibles.feature_flag.exception.ResourceNotFoundException;
import org.aibles.feature_flag.exception.UnauthorizedException;
import org.aibles.feature_flag.repository.EnvironmentRepository;
import org.aibles.feature_flag.repository.WebhookDeliveryAttemptRepository;
import org.aibles.feature_flag.repository.WebhookSubscriptionRepository;
import org.aibles.feature_flag.util.SecretCipher;
import org.aibles.feature_flag.webhook.SsrfGuard;
import org.aibles.feature_flag.webhook.WebhookUrlNotAllowedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WebhookSubscriptionServiceImplTest {

  @Mock WebhookSubscriptionRepository subscriptionRepository;
  @Mock WebhookDeliveryAttemptRepository deliveryAttemptRepository;
  @Mock EnvironmentRepository environmentRepository;
  @Mock PermissionService permissionService;
  @Mock SsrfGuard ssrfGuard;

  /** A real cipher, not a mock — the point of several tests is that ciphertext != plaintext. */
  SecretCipher secretCipher =
      new SecretCipher("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");

  WebhookSubscriptionServiceImpl service;

  UUID envId = UUID.randomUUID();
  UUID subscriptionId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    service =
        new WebhookSubscriptionServiceImpl(
            subscriptionRepository,
            deliveryAttemptRepository,
            environmentRepository,
            permissionService,
            secretCipher,
            ssrfGuard);
    when(environmentRepository.existsById(envId)).thenReturn(true);
    when(subscriptionRepository.save(any(WebhookSubscription.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    doNothing().when(permissionService).requireRoleForEnvironment(any(), any(MemberRole[].class));
  }

  private CreateWebhookSubscriptionRequest createRequest() {
    CreateWebhookSubscriptionRequest request = new CreateWebhookSubscriptionRequest();
    request.setEnvironmentId(envId);
    request.setUrl("https://example.com/hook");
    request.setEventTypes(Set.of(WebhookEventType.FLAG_STATE_CHANGED));
    return request;
  }

  private WebhookSubscription existing() {
    return WebhookSubscription.builder()
        .id(subscriptionId)
        .environmentId(envId)
        .url("https://example.com/hook")
        .secretCiphertext(secretCipher.encrypt("old-secret"))
        .enabled(true)
        .eventTypes(EnumSet.of(WebhookEventType.FLAG_STATE_CHANGED))
        .build();
  }

  // --- secret handling ----------------------------------------------------------------------

  @Test
  @DisplayName("create stores the secret encrypted, never in plaintext")
  void createStoresSecretEncrypted() {
    WebhookSubscriptionSecretResponse response = service.create(createRequest());

    ArgumentCaptor<WebhookSubscription> saved = ArgumentCaptor.forClass(WebhookSubscription.class);
    verify(subscriptionRepository).save(saved.capture());

    String stored = saved.getValue().getSecretCiphertext();
    assertThat(stored).isNotEqualTo(response.getSecret());
    assertThat(stored).doesNotContain(response.getSecret());
    // and it must still be recoverable — a hash would not be
    assertThat(secretCipher.decrypt(stored)).isEqualTo(response.getSecret());
  }

  @Test
  @DisplayName("create generates a secret when the caller supplies none")
  void createGeneratesSecretWhenAbsent() {
    WebhookSubscriptionSecretResponse response = service.create(createRequest());

    assertThat(response.getSecret()).matches("[0-9a-f]{64}");
  }

  @Test
  void createUsesTheCallerSuppliedSecret() {
    CreateWebhookSubscriptionRequest request = createRequest();
    request.setSecret("my-own-webhook-secret");

    assertThat(service.create(request).getSecret()).isEqualTo("my-own-webhook-secret");
  }

  @Test
  @DisplayName("read responses expose neither the plaintext secret nor the ciphertext")
  void readResponsesCarryNoSecret() {
    when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(existing()));

    WebhookSubscriptionResponse response = service.get(subscriptionId);

    assertThat(response.toString()).doesNotContain("old-secret").doesNotContain("secret");
  }

  @Test
  void rotateSecretIssuesANewValueAndReEncrypts() {
    WebhookSubscription subscription = existing();
    String before = subscription.getSecretCiphertext();
    when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));

    WebhookSubscriptionSecretResponse response = service.rotateSecret(subscriptionId);

    assertThat(response.getSecret()).matches("[0-9a-f]{64}").isNotEqualTo("old-secret");
    assertThat(subscription.getSecretCiphertext()).isNotEqualTo(before);
    assertThat(secretCipher.decrypt(subscription.getSecretCiphertext()))
        .isEqualTo(response.getSecret());
  }

  // --- SSRF guard is actually consulted -----------------------------------------------------

  @Test
  void createVerifiesTheUrlWithTheSsrfGuard() {
    service.create(createRequest());

    verify(ssrfGuard).verifyAllowed("https://example.com/hook");
  }

  @Test
  @DisplayName("a URL the guard rejects is not persisted")
  void createRejectsBlockedUrl() {
    doThrow(new WebhookUrlNotAllowedException("blocked"))
        .when(ssrfGuard)
        .verifyAllowed(anyString());

    assertThatThrownBy(() -> service.create(createRequest()))
        .isInstanceOf(WebhookUrlNotAllowedException.class);
    verify(subscriptionRepository, never()).save(any());
  }

  @Test
  @DisplayName(
      "update re-checks a changed URL — otherwise the guard could be bypassed after create")
  void updateVerifiesChangedUrl() {
    when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(existing()));
    UpdateWebhookSubscriptionRequest request = new UpdateWebhookSubscriptionRequest();
    request.setUrl("https://elsewhere.example.com/hook");

    service.update(subscriptionId, request);

    verify(ssrfGuard).verifyAllowed("https://elsewhere.example.com/hook");
  }

  @Test
  void updateDoesNotTouchTheGuardWhenUrlIsUnchanged() {
    when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(existing()));
    UpdateWebhookSubscriptionRequest request = new UpdateWebhookSubscriptionRequest();
    request.setEnabled(false);

    service.update(subscriptionId, request);

    verify(ssrfGuard, never()).verifyAllowed(anyString());
  }

  // --- permissions --------------------------------------------------------------------------

  @Test
  void createRequiresOwnerOrAdmin() {
    doThrow(new UnauthorizedException("nope"))
        .when(permissionService)
        .requireRoleForEnvironment(any(), any(MemberRole[].class));

    assertThatThrownBy(() -> service.create(createRequest()))
        .isInstanceOf(UnauthorizedException.class);
    verify(subscriptionRepository, never()).save(any());
  }

  @Test
  void deleteRequiresPermissionOnTheSubscriptionsEnvironment() {
    when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(existing()));
    doThrow(new UnauthorizedException("nope"))
        .when(permissionService)
        .requireRoleForEnvironment(any(), any(MemberRole[].class));

    assertThatThrownBy(() -> service.delete(subscriptionId))
        .isInstanceOf(UnauthorizedException.class);
    verify(subscriptionRepository, never()).delete(any());
  }

  // --- not-found paths ----------------------------------------------------------------------

  @Test
  void createRejectsUnknownEnvironment() {
    when(environmentRepository.existsById(envId)).thenReturn(false);

    assertThatThrownBy(() -> service.create(createRequest()))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  void getRejectsUnknownSubscription() {
    when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.get(subscriptionId))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  // --- update semantics ---------------------------------------------------------------------

  @Test
  @DisplayName("null fields leave the subscription unchanged")
  void updateIgnoresNullFields() {
    WebhookSubscription subscription = existing();
    when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));

    service.update(subscriptionId, new UpdateWebhookSubscriptionRequest());

    assertThat(subscription.getUrl()).isEqualTo("https://example.com/hook");
    assertThat(subscription.isEnabled()).isTrue();
    assertThat(subscription.getEventTypes()).containsExactly(WebhookEventType.FLAG_STATE_CHANGED);
  }

  @Test
  void updateReplacesEventTypes() {
    WebhookSubscription subscription = existing();
    when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));
    UpdateWebhookSubscriptionRequest request = new UpdateWebhookSubscriptionRequest();
    request.setEventTypes(Set.of(WebhookEventType.FLAG_CREATED, WebhookEventType.FLAG_ARCHIVED));

    service.update(subscriptionId, request);

    assertThat(subscription.getEventTypes())
        .containsExactlyInAnyOrder(WebhookEventType.FLAG_CREATED, WebhookEventType.FLAG_ARCHIVED);
  }

  @Test
  @DisplayName("an empty event-type set is ignored — a subscription must listen for something")
  void updateIgnoresEmptyEventTypes() {
    WebhookSubscription subscription = existing();
    when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));
    UpdateWebhookSubscriptionRequest request = new UpdateWebhookSubscriptionRequest();
    request.setEventTypes(Set.of());

    service.update(subscriptionId, request);

    assertThat(subscription.getEventTypes()).containsExactly(WebhookEventType.FLAG_STATE_CHANGED);
  }
}
