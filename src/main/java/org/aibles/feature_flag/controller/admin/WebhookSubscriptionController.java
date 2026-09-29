package org.aibles.feature_flag.controller.admin;

import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.aibles.feature_flag.dto.request.CreateWebhookSubscriptionRequest;
import org.aibles.feature_flag.dto.request.UpdateWebhookSubscriptionRequest;
import org.aibles.feature_flag.dto.response.PageResponse;
import org.aibles.feature_flag.dto.response.WebhookDeliveryAttemptResponse;
import org.aibles.feature_flag.dto.response.WebhookSubscriptionResponse;
import org.aibles.feature_flag.dto.response.WebhookSubscriptionSecretResponse;
import org.aibles.feature_flag.service.WebhookSubscriptionService;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/webhooks")
@RequiredArgsConstructor
public class WebhookSubscriptionController {

  private final WebhookSubscriptionService webhookSubscriptionService;

  /** Returns the plaintext secret exactly once — see {@link WebhookSubscriptionSecretResponse}. */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public WebhookSubscriptionSecretResponse create(
      @Valid @RequestBody CreateWebhookSubscriptionRequest request) {
    return webhookSubscriptionService.create(request);
  }

  @GetMapping
  public PageResponse<WebhookSubscriptionResponse> listByEnvironment(
      @RequestParam UUID environmentId,
      @ParameterObject
          @PageableDefault(
              size = 20,
              sort = {"createdAt", "id"},
              direction = Sort.Direction.ASC)
          Pageable pageable) {
    return PageResponse.from(webhookSubscriptionService.listByEnvironment(environmentId, pageable));
  }

  @GetMapping("/{webhookId}")
  public WebhookSubscriptionResponse get(@PathVariable UUID webhookId) {
    return webhookSubscriptionService.get(webhookId);
  }

  @PutMapping("/{webhookId}")
  public WebhookSubscriptionResponse update(
      @PathVariable UUID webhookId, @Valid @RequestBody UpdateWebhookSubscriptionRequest request) {
    return webhookSubscriptionService.update(webhookId, request);
  }

  @DeleteMapping("/{webhookId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID webhookId) {
    webhookSubscriptionService.delete(webhookId);
  }

  @PostMapping("/{webhookId}/secret/rotate")
  public WebhookSubscriptionSecretResponse rotateSecret(@PathVariable UUID webhookId) {
    return webhookSubscriptionService.rotateSecret(webhookId);
  }

  /** Delivery history for debugging a failing endpoint. Newest first. */
  @GetMapping("/{webhookId}/deliveries")
  public PageResponse<WebhookDeliveryAttemptResponse> listDeliveries(
      @PathVariable UUID webhookId,
      @ParameterObject
          @PageableDefault(
              size = 20,
              sort = {"createdAt", "id"},
              direction = Sort.Direction.DESC)
          Pageable pageable) {
    return PageResponse.from(webhookSubscriptionService.listDeliveryAttempts(webhookId, pageable));
  }
}
