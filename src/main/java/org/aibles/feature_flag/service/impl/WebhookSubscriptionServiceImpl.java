package org.aibles.feature_flag.service.impl;

import java.util.EnumSet;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.aibles.feature_flag.domain.entity.WebhookDeliveryAttempt;
import org.aibles.feature_flag.domain.entity.WebhookSubscription;
import org.aibles.feature_flag.domain.enums.MemberRole;
import org.aibles.feature_flag.dto.request.CreateWebhookSubscriptionRequest;
import org.aibles.feature_flag.dto.request.UpdateWebhookSubscriptionRequest;
import org.aibles.feature_flag.dto.response.WebhookDeliveryAttemptResponse;
import org.aibles.feature_flag.dto.response.WebhookSubscriptionResponse;
import org.aibles.feature_flag.dto.response.WebhookSubscriptionSecretResponse;
import org.aibles.feature_flag.exception.ResourceNotFoundException;
import org.aibles.feature_flag.repository.EnvironmentRepository;
import org.aibles.feature_flag.repository.WebhookDeliveryAttemptRepository;
import org.aibles.feature_flag.repository.WebhookSubscriptionRepository;
import org.aibles.feature_flag.service.WebhookSubscriptionService;
import org.aibles.feature_flag.util.ApiKeyGenerator;
import org.aibles.feature_flag.util.SecretCipher;
import org.aibles.feature_flag.webhook.SsrfGuard;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WebhookSubscriptionServiceImpl implements WebhookSubscriptionService {

  private final WebhookSubscriptionRepository subscriptionRepository;
  private final WebhookDeliveryAttemptRepository deliveryAttemptRepository;
  private final EnvironmentRepository environmentRepository;
  private final PermissionService permissionService;
  private final SecretCipher secretCipher;
  private final SsrfGuard ssrfGuard;

  @Override
  @Transactional
  public WebhookSubscriptionSecretResponse create(CreateWebhookSubscriptionRequest request) {
    permissionService.requireRoleForEnvironment(
        request.getEnvironmentId(), MemberRole.OWNER, MemberRole.ADMIN);
    if (!environmentRepository.existsById(request.getEnvironmentId())) {
      throw new ResourceNotFoundException("Environment", request.getEnvironmentId());
    }
    // Reject an internal URL up front so the operator gets a 400 now, rather than a
    // subscription that silently fails every delivery. Re-checked at delivery time
    // because DNS can change — see SsrfGuard.
    ssrfGuard.verifyAllowed(request.getUrl());

    // ApiKeyGenerator is a generic 256-bit SecureRandom hex token generator; reused here
    // rather than duplicating the same SecureRandom setup for webhook secrets.
    String plaintextSecret =
        request.getSecret() == null || request.getSecret().isBlank()
            ? ApiKeyGenerator.generate()
            : request.getSecret();

    WebhookSubscription subscription =
        WebhookSubscription.builder()
            .environmentId(request.getEnvironmentId())
            .url(request.getUrl())
            .secretCiphertext(secretCipher.encrypt(plaintextSecret))
            .eventTypes(EnumSet.copyOf(request.getEventTypes()))
            .enabled(true)
            .build();

    return toSecretResponse(subscriptionRepository.save(subscription), plaintextSecret);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<WebhookSubscriptionResponse> listByEnvironment(
      UUID environmentId, Pageable pageable) {
    permissionService.requireRoleForEnvironment(
        environmentId, MemberRole.OWNER, MemberRole.ADMIN, MemberRole.VIEWER);
    return subscriptionRepository
        .findAllByEnvironmentId(environmentId, pageable)
        .map(this::toResponse);
  }

  @Override
  @Transactional(readOnly = true)
  public WebhookSubscriptionResponse get(UUID id) {
    WebhookSubscription subscription = findById(id);
    permissionService.requireRoleForEnvironment(
        subscription.getEnvironmentId(), MemberRole.OWNER, MemberRole.ADMIN, MemberRole.VIEWER);
    return toResponse(subscription);
  }

  @Override
  @Transactional
  public WebhookSubscriptionResponse update(UUID id, UpdateWebhookSubscriptionRequest request) {
    WebhookSubscription subscription = findById(id);
    permissionService.requireRoleForEnvironment(
        subscription.getEnvironmentId(), MemberRole.OWNER, MemberRole.ADMIN);

    if (request.getUrl() != null) {
      ssrfGuard.verifyAllowed(request.getUrl());
      subscription.setUrl(request.getUrl());
    }
    if (request.getEventTypes() != null && !request.getEventTypes().isEmpty()) {
      subscription.setEventTypes(EnumSet.copyOf(request.getEventTypes()));
    }
    if (request.getEnabled() != null) {
      subscription.setEnabled(request.getEnabled());
    }
    return toResponse(subscriptionRepository.save(subscription));
  }

  @Override
  @Transactional
  public void delete(UUID id) {
    WebhookSubscription subscription = findById(id);
    permissionService.requireRoleForEnvironment(
        subscription.getEnvironmentId(), MemberRole.OWNER, MemberRole.ADMIN);
    // Delivery attempts cascade at the DB level (migration 012).
    subscriptionRepository.delete(subscription);
  }

  @Override
  @Transactional
  public WebhookSubscriptionSecretResponse rotateSecret(UUID id) {
    WebhookSubscription subscription = findById(id);
    permissionService.requireRoleForEnvironment(
        subscription.getEnvironmentId(), MemberRole.OWNER, MemberRole.ADMIN);

    String plaintextSecret = ApiKeyGenerator.generate();
    subscription.setSecretCiphertext(secretCipher.encrypt(plaintextSecret));
    return toSecretResponse(subscriptionRepository.save(subscription), plaintextSecret);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<WebhookDeliveryAttemptResponse> listDeliveryAttempts(UUID id, Pageable pageable) {
    WebhookSubscription subscription = findById(id);
    permissionService.requireRoleForEnvironment(
        subscription.getEnvironmentId(), MemberRole.OWNER, MemberRole.ADMIN, MemberRole.VIEWER);
    return deliveryAttemptRepository.findAllBySubscriptionId(id, pageable).map(this::toResponse);
  }

  private WebhookSubscription findById(UUID id) {
    return subscriptionRepository
        .findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Webhook subscription", id));
  }

  private WebhookSubscriptionResponse toResponse(WebhookSubscription s) {
    return WebhookSubscriptionResponse.builder()
        .id(s.getId())
        .environmentId(s.getEnvironmentId())
        .url(s.getUrl())
        .eventTypes(s.getEventTypes())
        .enabled(s.isEnabled())
        .createdAt(s.getCreatedAt())
        .updatedAt(s.getUpdatedAt())
        .build();
  }

  private WebhookSubscriptionSecretResponse toSecretResponse(
      WebhookSubscription s, String plaintextSecret) {
    return WebhookSubscriptionSecretResponse.builder()
        .id(s.getId())
        .environmentId(s.getEnvironmentId())
        .url(s.getUrl())
        .eventTypes(s.getEventTypes())
        .enabled(s.isEnabled())
        .secret(plaintextSecret)
        .createdAt(s.getCreatedAt())
        .build();
  }

  private WebhookDeliveryAttemptResponse toResponse(WebhookDeliveryAttempt a) {
    return WebhookDeliveryAttemptResponse.builder()
        .id(a.getId())
        .subscriptionId(a.getSubscriptionId())
        .eventType(a.getEventType())
        .attempt(a.getAttempt())
        .succeeded(a.isSucceeded())
        .responseStatus(a.getResponseStatus())
        .error(a.getError())
        .durationMs(a.getDurationMs())
        .createdAt(a.getCreatedAt())
        .build();
  }
}
