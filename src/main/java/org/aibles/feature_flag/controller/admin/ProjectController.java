package org.aibles.feature_flag.controller.admin;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.aibles.feature_flag.dto.request.CreateProjectRequest;
import org.aibles.feature_flag.dto.request.UpdateProjectRequest;
import org.aibles.feature_flag.dto.response.ProjectResponse;
import org.aibles.feature_flag.service.ProjectService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
public class ProjectController {

  private final ProjectService projectService;

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ProjectResponse create(@Valid @RequestBody CreateProjectRequest request) {
    return projectService.create(request);
  }

  @GetMapping
  public List<ProjectResponse> listByOrganisation(@RequestParam UUID organisationId) {
    return projectService.listByOrganisation(organisationId);
  }

  @GetMapping("/{projectId}")
  public ProjectResponse get(@PathVariable UUID projectId) {
    return projectService.get(projectId);
  }

  @PutMapping("/{projectId}")
  public ProjectResponse update(
      @PathVariable UUID projectId, @Valid @RequestBody UpdateProjectRequest request) {
    return projectService.update(projectId, request);
  }

  @DeleteMapping("/{projectId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID projectId) {
    projectService.delete(projectId);
  }
}
