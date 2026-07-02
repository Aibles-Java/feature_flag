package org.aibles.feature_flag.controller.admin;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.aibles.feature_flag.dto.request.CreateCustomRoleRequest;
import org.aibles.feature_flag.dto.response.CustomRoleResponse;
import org.aibles.feature_flag.service.CustomRoleService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Manages organization-scoped custom roles. */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/roles")
@RequiredArgsConstructor
public class CustomRoleController {

    private final CustomRoleService customRoleService;

    @GetMapping
    public List<CustomRoleResponse> list(@PathVariable UUID orgId) {
        return customRoleService.list(orgId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CustomRoleResponse create(@PathVariable UUID orgId,
                                     @Valid @RequestBody CreateCustomRoleRequest request) {
        return customRoleService.create(orgId, request);
    }

    @PutMapping("/{roleId}")
    public CustomRoleResponse update(@PathVariable UUID orgId,
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
