package org.aibles.feature_flag.service.impl;

import lombok.RequiredArgsConstructor;
import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.domain.entity.OrganizationMember;
import org.aibles.feature_flag.domain.entity.Project;
import org.aibles.feature_flag.domain.enums.MemberRole;
import org.aibles.feature_flag.exception.ResourceNotFoundException;
import org.aibles.feature_flag.exception.UnauthorizedException;
import org.aibles.feature_flag.repository.EnvironmentRepository;
import org.aibles.feature_flag.repository.OrganizationMemberRepository;
import org.aibles.feature_flag.repository.ProjectRepository;
import org.aibles.feature_flag.security.UserPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PermissionService {

    private final OrganizationMemberRepository memberRepository;
    private final ProjectRepository projectRepository;
    private final EnvironmentRepository environmentRepository;

    public UUID currentUserId() {
        UserPrincipal principal = (UserPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return principal.getId();
    }

    public void requireRole(UUID orgId, MemberRole... roles) {
        UUID userId = currentUserId();
        OrganizationMember member = memberRepository.findByOrganizationIdAndUserId(orgId, userId)
                .orElseThrow(() -> new UnauthorizedException("You are not a member of this organisation"));
        if (Arrays.stream(roles).noneMatch(r -> r == member.getRole())) {
            throw new UnauthorizedException("Insufficient permissions. Required: " + Arrays.toString(roles));
        }
    }

    public void requireRoleForProject(UUID projectId, MemberRole... roles) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project", projectId));
        requireRole(project.getOrganization().getId(), roles);
    }

    public void requireRoleForEnvironment(UUID environmentId, MemberRole... roles) {
        Environment env = environmentRepository.findById(environmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Environment", environmentId));
        requireRoleForProject(env.getProject().getId(), roles);
    }

    public boolean isMember(UUID orgId) {
        UUID userId = currentUserId();
        return memberRepository.existsByOrganizationIdAndUserId(orgId, userId);
    }
}
