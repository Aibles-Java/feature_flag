package org.aibles.feature_flag.controller.admin;

import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.aibles.feature_flag.dto.request.CreateProjectGrantRequest;
import org.aibles.feature_flag.dto.response.PageResponse;
import org.aibles.feature_flag.dto.response.ProjectGrantResponse;
import org.aibles.feature_flag.service.ProjectGrantService;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Manages PROJECT-scoped role grants. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/members")
@RequiredArgsConstructor
public class ProjectMemberController {

  private final ProjectGrantService projectGrantService;

  @GetMapping
  public PageResponse<ProjectGrantResponse> list(
      @PathVariable UUID projectId,
      @ParameterObject
          @PageableDefault(
              size = 20,
              sort = {"createdAt", "id"},
              direction = Sort.Direction.ASC)
          Pageable pageable) {
    return PageResponse.from(projectGrantService.listGrants(projectId, pageable));
  }

  @PostMapping
  public ProjectGrantResponse upsert(
      @PathVariable UUID projectId, @Valid @RequestBody CreateProjectGrantRequest request) {
    return projectGrantService.upsertGrant(projectId, request);
  }

  @DeleteMapping("/{userId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void revoke(@PathVariable UUID projectId, @PathVariable UUID userId) {
    projectGrantService.revokeGrant(projectId, userId);
  }
}
