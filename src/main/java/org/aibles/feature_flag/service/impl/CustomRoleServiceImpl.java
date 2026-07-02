package org.aibles.feature_flag.service.impl;

import lombok.RequiredArgsConstructor;
import org.aibles.feature_flag.domain.entity.CustomRole;
import org.aibles.feature_flag.domain.entity.Organization;
import org.aibles.feature_flag.domain.enums.Action;
import org.aibles.feature_flag.dto.request.CreateCustomRoleRequest;
import org.aibles.feature_flag.dto.response.CustomRoleResponse;
import org.aibles.feature_flag.exception.DuplicateResourceException;
import org.aibles.feature_flag.exception.ResourceNotFoundException;
import org.aibles.feature_flag.exception.UnauthorizedException;
import org.aibles.feature_flag.repository.CustomRoleRepository;
import org.aibles.feature_flag.repository.OrganizationRepository;
import org.aibles.feature_flag.service.CustomRoleService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CustomRoleServiceImpl implements CustomRoleService {

    private final CustomRoleRepository customRoleRepository;
    private final OrganizationRepository organizationRepository;
    private final PermissionService permissionService;

    @Override
    @Transactional(readOnly = true)
    public List<CustomRoleResponse> list(UUID orgId) {
        permissionService.check(Action.ROLE_MANAGE, PermissionService.ResourceRef.org(orgId));
        return customRoleRepository.findAllByOrganizationId(orgId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public CustomRoleResponse create(UUID orgId, CreateCustomRoleRequest request) {
        permissionService.check(Action.ROLE_MANAGE, PermissionService.ResourceRef.org(orgId));
        requireCanConfer(orgId, request.getActions());
        Organization org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organisation", orgId));
        if (customRoleRepository.existsByOrganizationIdAndName(orgId, request.getName())) {
            throw new DuplicateResourceException("Custom role name already exists: " + request.getName());
        }
        CustomRole role = CustomRole.builder()
                .organization(org)
                .name(request.getName())
                .actions(new HashSet<>(request.getActions()))
                .build();
        return toResponse(customRoleRepository.save(role));
    }

    @Override
    @Transactional
    public CustomRoleResponse update(UUID orgId, UUID roleId, CreateCustomRoleRequest request) {
        permissionService.check(Action.ROLE_MANAGE, PermissionService.ResourceRef.org(orgId));
        CustomRole role = findInOrg(orgId, roleId);
        requireCanConfer(orgId, request.getActions());
        requireCanConfer(orgId, role.getActions());
        role.setName(request.getName());
        role.setActions(new HashSet<>(request.getActions()));
        return toResponse(customRoleRepository.save(role));
    }

    @Override
    @Transactional
    public void delete(UUID orgId, UUID roleId) {
        permissionService.check(Action.ROLE_MANAGE, PermissionService.ResourceRef.org(orgId));
        CustomRole role = findInOrg(orgId, roleId);
        // Deletion cascades to grants, so bound it by the same ceiling as granting.
        requireCanConfer(orgId, role.getActions());
        customRoleRepository.delete(role);
    }

    /** A custom role may only contain actions the acting user holds at org scope. */
    private void requireCanConfer(UUID orgId, Set<Action> actions) {
        Set<Action> own = permissionService.effectiveActionsForOrg(permissionService.currentUserId(), orgId);
        if (!own.containsAll(actions)) {
            throw new UnauthorizedException("A custom role cannot include actions you do not have");
        }
    }

    private CustomRole findInOrg(UUID orgId, UUID roleId) {
        CustomRole role = customRoleRepository.findById(roleId)
                .orElseThrow(() -> new ResourceNotFoundException("CustomRole", roleId));
        if (!role.getOrganization().getId().equals(orgId)) {
            throw new ResourceNotFoundException("CustomRole", roleId);
        }
        return role;
    }

    private CustomRoleResponse toResponse(CustomRole role) {
        return CustomRoleResponse.builder()
                .id(role.getId())
                .organizationId(role.getOrganization().getId())
                .name(role.getName())
                .actions(role.getActions())
                .build();
    }
}
