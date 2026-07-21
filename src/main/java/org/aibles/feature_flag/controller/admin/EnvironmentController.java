package org.aibles.feature_flag.controller.admin;

import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.aibles.feature_flag.dto.request.CreateEnvironmentRequest;
import org.aibles.feature_flag.dto.request.UpdateEnvironmentRequest;
import org.aibles.feature_flag.dto.response.EnvironmentResponse;
import org.aibles.feature_flag.dto.response.EnvironmentSecretResponse;
import org.aibles.feature_flag.dto.response.PageResponse;
import org.aibles.feature_flag.service.EnvironmentService;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/environments")
@RequiredArgsConstructor
public class EnvironmentController {

  private final EnvironmentService environmentService;

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public EnvironmentSecretResponse create(@Valid @RequestBody CreateEnvironmentRequest request) {
    return environmentService.create(request);
  }

  @GetMapping
  public PageResponse<EnvironmentResponse> listByProject(
      @RequestParam UUID projectId,
      @ParameterObject
          @PageableDefault(
              size = 20,
              sort = {"createdAt", "id"},
              direction = Sort.Direction.ASC)
          Pageable pageable) {
    return PageResponse.from(environmentService.listByProject(projectId, pageable));
  }

  @GetMapping("/{envId}")
  public EnvironmentResponse get(@PathVariable UUID envId) {
    return environmentService.get(envId);
  }

  @PutMapping("/{envId}")
  public EnvironmentResponse update(
      @PathVariable UUID envId, @Valid @RequestBody UpdateEnvironmentRequest request) {
    return environmentService.update(envId, request);
  }

  @DeleteMapping("/{envId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID envId) {
    environmentService.delete(envId);
  }

  @PostMapping("/{envId}/api-key/rotate")
  public EnvironmentSecretResponse rotateApiKey(@PathVariable UUID envId) {
    return environmentService.rotateApiKey(envId);
  }
}
