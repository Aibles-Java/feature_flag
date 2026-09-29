package org.aibles.feature_flag.controller.admin;

import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.aibles.feature_flag.dto.request.CreateFeatureFlagRequest;
import org.aibles.feature_flag.dto.request.UpdateFeatureFlagRequest;
import org.aibles.feature_flag.dto.request.UpdateFlagStateRequest;
import org.aibles.feature_flag.dto.response.FeatureFlagResponse;
import org.aibles.feature_flag.dto.response.FlagStateResponse;
import org.aibles.feature_flag.dto.response.PageResponse;
import org.aibles.feature_flag.service.FeatureFlagService;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/flags")
@RequiredArgsConstructor
public class FeatureFlagController {

  private final FeatureFlagService featureFlagService;

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public FeatureFlagResponse create(@Valid @RequestBody CreateFeatureFlagRequest request) {
    return featureFlagService.create(request);
  }

  @GetMapping
  public PageResponse<FeatureFlagResponse> listByProject(
      @RequestParam UUID projectId,
      @ParameterObject
          @PageableDefault(
              size = 20,
              sort = {"createdAt", "id"},
              direction = Sort.Direction.ASC)
          Pageable pageable) {
    return PageResponse.from(featureFlagService.listByProject(projectId, pageable));
  }

  @GetMapping("/{flagId}")
  public FeatureFlagResponse get(@PathVariable UUID flagId) {
    return featureFlagService.get(flagId);
  }

  @PutMapping("/{flagId}")
  public FeatureFlagResponse update(
      @PathVariable UUID flagId, @Valid @RequestBody UpdateFeatureFlagRequest request) {
    return featureFlagService.update(flagId, request);
  }

  @DeleteMapping("/{flagId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void archive(@PathVariable UUID flagId) {
    featureFlagService.archive(flagId);
  }

  @PostMapping("/{flagId}/unarchive")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void unarchive(@PathVariable UUID flagId) {
    featureFlagService.unarchive(flagId);
  }

  @GetMapping("/archived")
  public PageResponse<FeatureFlagResponse> listArchived(
      @RequestParam UUID projectId,
      @ParameterObject
          @PageableDefault(
              size = 20,
              sort = {"createdAt", "id"},
              direction = Sort.Direction.ASC)
          Pageable pageable) {
    return PageResponse.from(featureFlagService.listArchivedByProject(projectId, pageable));
  }

  @GetMapping("/{flagId}/environments/{envId}")
  public FlagStateResponse getState(@PathVariable UUID flagId, @PathVariable UUID envId) {
    return featureFlagService.getState(flagId, envId);
  }

  @PutMapping("/{flagId}/environments/{envId}")
  public FlagStateResponse updateState(
      @PathVariable UUID flagId,
      @PathVariable UUID envId,
      @Valid @RequestBody UpdateFlagStateRequest request) {
    return featureFlagService.updateState(flagId, envId, request);
  }
}
