package org.aibles.feature_flag.service.impl;

import lombok.RequiredArgsConstructor;
import org.aibles.feature_flag.domain.entity.CustomRole;
import org.aibles.feature_flag.domain.entity.PermissionGrant;
import org.aibles.feature_flag.domain.entity.Project;
import org.aibles.feature_flag.domain.entity.User;
import org.aibles.feature_flag.domain.enums.Action;
import org.aibles.feature_flag.domain.enums.ScopeType;
import org.aibles.feature_flag.dto.request.CreateProjectGrantRequest;
import org.aibles.feature_flag.dto.response.ProjectGrantResponse;
import org.aibles.feature_flag.exception.ResourceNotFoundException;
import org.aibles.feature_flag.exception.UnauthorizedException;
import org.aibles.feature_flag.repository.CustomRoleRepository;
import org.aibles.feature_flag.repository.OrganizationMemberRepository;
import org.aibles.feature_flag.repository.PermissionGrantRepository;
import org.aibles.feature_flag.repository.ProjectRepository;
import org.aibles.feature_flag.repository.UserRepository;
import org.aibles.feature_flag.service.ProjectGrantService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProjectGrantServiceImpl implements ProjectGrantService {

    private final PermissionGrantRepository grantRepository;
    private final ProjectRepository projectRepository;
    private final OrganizationMemberRepository memberRepository;
    private final CustomRoleRepository customRoleRepository;
    private final UserRepository userRepository;
    private final PermissionService permissionService;

    @Override
    @Transactional(readOnly = true)
    public List<ProjectGrantResponse> listGrants(UUID projectId) {
        permissionService.check(Action.GRANT_MANAGE, PermissionService.ResourceRef.project(projectId));
        return grantRepository.findAllByScopeTypeAndScopeId(ScopeType.PROJECT, projectId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public ProjectGrantResponse upsertGrant(UUID projectId, CreateProjectGrantRequest request) {
        permissionService.check(Action.GRANT_MANAGE, PermissionService.ResourceRef.project(projectId));

        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project", projectId));

        boolean builtIn = request.getRole() != null;
        boolean custom = request.getCustomRoleId() != null;
        if (builtIn == custom) {
            throw new IllegalArgumentException("Provide exactly one of role or customRoleId");
        }

        CustomRole customRole = null;
        Set<Action> targetActions;
        if (builtIn) {
            targetActions = PermissionService.actionsForRole(request.getRole());
        } else {
            customRole = customRoleRepository.findById(request.getCustomRoleId())
                    .orElseThrow(() -> new ResourceNotFoundException("CustomRole", request.getCustomRoleId()));
            if (!customRole.getOrganization().getId().equals(project.getOrganization().getId())) {
                throw new ResourceNotFoundException("CustomRole", request.getCustomRoleId());
            }
            targetActions = customRole.getActions();
        }

        requireGranterCanConfer(projectId, targetActions);

        User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User", request.getUserId()));

        if (!memberRepository.existsByOrganizationIdAndUserId(project.getOrganization().getId(), user.getId())) {
            throw new ResourceNotFoundException("User is not a member of this project's organisation");
        }

        PermissionGrant grant = grantRepository
                .findByUser_IdAndScopeTypeAndScopeId(user.getId(), ScopeType.PROJECT, projectId)
                .orElseGet(() -> PermissionGrant.builder()
                        .user(user)
                        .scopeType(ScopeType.PROJECT)
                        .scopeId(projectId)
                        .build());
        grant.setRole(request.getRole());
        grant.setCustomRole(customRole);

        return toResponse(grantRepository.save(grant));
    }

    @Override
    @Transactional
    public void revokeGrant(UUID projectId, UUID userId) {
        permissionService.check(Action.GRANT_MANAGE, PermissionService.ResourceRef.project(projectId));

        PermissionGrant grant = grantRepository
                .findByUser_IdAndScopeTypeAndScopeId(userId, ScopeType.PROJECT, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Grant not found for this project and user"));

        requireGranterCanConfer(projectId, PermissionService.grantActions(grant));

        grantRepository.delete(grant);
    }

    /** A caller may only grant or revoke permissions they themselves hold on the project. */
    private void requireGranterCanConfer(UUID projectId, Set<Action> targetActions) {
        Set<Action> granterActions =
                permissionService.effectiveActionsForProject(permissionService.currentUserId(), projectId);
        if (!granterActions.containsAll(targetActions)) {
            throw new UnauthorizedException("You cannot grant or revoke permissions beyond your own");
        }
    }

    private ProjectGrantResponse toResponse(PermissionGrant grant) {
        User user = grant.getUser();
        CustomRole customRole = grant.getCustomRole();
        return ProjectGrantResponse.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .role(grant.getRole())
                .customRoleId(customRole != null ? customRole.getId() : null)
                .customRoleName(customRole != null ? customRole.getName() : null)
                .build();
    }
}
