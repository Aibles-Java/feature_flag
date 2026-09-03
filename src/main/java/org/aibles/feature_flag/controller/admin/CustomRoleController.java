package org.aibles.feature_flag.controller.admin;

import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.aibles.feature_flag.dto.request.CreateCustomRoleRequest;
import org.aibles.feature_flag.dto.response.CustomRoleResponse;
import org.aibles.feature_flag.dto.response.PageResponse;
import org.aibles.feature_flag.service.CustomRoleService;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Manages organization-scoped custom roles. */
@RestController
@RequestMapping("/api/v1/organisations/{orgId}/roles")
@RequiredArgsConstructor
public class CustomRoleController {

  private final CustomRoleService customRoleService;

  @GetMapping
  public PageResponse<CustomRoleResponse> list(
      @PathVariable UUID orgId,
      @ParameterObject
          @PageableDefault(
              size = 20,
              sort = {"createdAt", "id"},
              direction = Sort.Direction.ASC)
          Pageable pageable) {
    return PageResponse.from(customRoleService.list(orgId, pageable));
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public CustomRoleResponse create(
      @PathVariable UUID orgId, @Valid @RequestBody CreateCustomRoleRequest request) {
    return customRoleService.create(orgId, request);
  }

  @PutMapping("/{roleId}")
  public CustomRoleResponse update(
      @PathVariable UUID orgId,
      @PathVariable UUID roleId,
      @Valid @RequestBody CreateCustomRoleRequest request) {
    return customRoleService.update(orgId, roleId, request);
  }

  @DeleteMapping("/{roleId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID orgId, @PathVariable UUID roleId) {
    customRoleService.delete(orgId, roleId);
  }
}
