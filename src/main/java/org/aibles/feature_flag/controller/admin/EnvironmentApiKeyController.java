package org.aibles.feature_flag.controller.admin;

import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.aibles.feature_flag.dto.request.CreateApiKeyRequest;
import org.aibles.feature_flag.dto.request.RotateApiKeyRequest;
import org.aibles.feature_flag.dto.response.ApiKeyResponse;
import org.aibles.feature_flag.dto.response.ApiKeySecretResponse;
import org.aibles.feature_flag.dto.response.PageResponse;
import org.aibles.feature_flag.service.EnvironmentApiKeyService;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/environments/{envId}/api-keys")
@RequiredArgsConstructor
public class EnvironmentApiKeyController {

  private final EnvironmentApiKeyService apiKeyService;

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ApiKeySecretResponse create(
      @PathVariable UUID envId, @Valid @RequestBody CreateApiKeyRequest request) {
    return apiKeyService.create(envId, request);
  }

  @GetMapping
  public PageResponse<ApiKeyResponse> list(
      @PathVariable UUID envId,
      @ParameterObject
          @PageableDefault(
              size = 20,
              sort = {"createdAt", "id"},
              direction = Sort.Direction.ASC)
          Pageable pageable) {
    return PageResponse.from(apiKeyService.list(envId, pageable));
  }

  @DeleteMapping("/{keyId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void revoke(@PathVariable UUID envId, @PathVariable UUID keyId) {
    apiKeyService.revoke(envId, keyId);
  }

  @PostMapping("/{keyId}/rotate")
  public ApiKeySecretResponse rotate(
      @PathVariable UUID envId,
      @PathVariable UUID keyId,
      @Valid @RequestBody(required = false) RotateApiKeyRequest request) {
    return apiKeyService.rotate(
        envId, keyId, request != null ? request : new RotateApiKeyRequest());
  }
}
