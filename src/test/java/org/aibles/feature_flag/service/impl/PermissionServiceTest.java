package org.aibles.feature_flag.service.impl;

import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.domain.entity.Organization;
import org.aibles.feature_flag.domain.entity.OrganizationMember;
import org.aibles.feature_flag.domain.entity.Project;
import org.aibles.feature_flag.domain.entity.User;
import org.aibles.feature_flag.domain.enums.MemberRole;
import org.aibles.feature_flag.exception.ResourceNotFoundException;
import org.aibles.feature_flag.exception.UnauthorizedException;
import org.aibles.feature_flag.repository.EnvironmentRepository;
import org.aibles.feature_flag.repository.OrganizationMemberRepository;
import org.aibles.feature_flag.repository.ProjectRepository;
import org.aibles.feature_flag.security.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PermissionService}: the OWNER/ADMIN/VIEWER role gate that guards
 * every mutating operation. The current user is injected via {@link SecurityContextHolder}.
 */
@ExtendWith(MockitoExtension.class)
class PermissionServiceTest {

    @Mock
    private OrganizationMemberRepository memberRepository;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private EnvironmentRepository environmentRepository;

    private PermissionService permissionService;

    private final UUID userId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        permissionService = new PermissionService(memberRepository, projectRepository, environmentRepository);
        UserPrincipal principal = UserPrincipal.from(User.builder()
                .id(userId)
                .email("member@example.com")
                .passwordHash("x")
                .build());
        SecurityContextHolder.setContext(new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(principal, null)));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private OrganizationMember memberWithRole(MemberRole role) {
        return OrganizationMember.builder().role(role).build();
    }

    @Test
    void currentUserIdReadsPrincipalFromSecurityContext() {
        assertThat(permissionService.currentUserId()).isEqualTo(userId);
    }

    @Test
    void requireRolePassesWhenMemberRoleIsAllowed() {
        when(memberRepository.findByOrganizationIdAndUserId(orgId, userId))
                .thenReturn(Optional.of(memberWithRole(MemberRole.OWNER)));

        // Should not throw.
        permissionService.requireRole(orgId, MemberRole.OWNER, MemberRole.ADMIN);
    }

    @Test
    void requireRoleThrowsWhenMemberRoleIsNotAllowed() {
        when(memberRepository.findByOrganizationIdAndUserId(orgId, userId))
                .thenReturn(Optional.of(memberWithRole(MemberRole.VIEWER)));

        assertThatThrownBy(() -> permissionService.requireRole(orgId, MemberRole.OWNER, MemberRole.ADMIN))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("Insufficient permissions");
    }

    @Test
    void requireRoleThrowsWhenNotAMember() {
        when(memberRepository.findByOrganizationIdAndUserId(orgId, userId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> permissionService.requireRole(orgId, MemberRole.OWNER))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("not a member");
    }

    /**
     * For every role: a check that requires exactly that role passes, and a check that
     * requires only the other two roles is rejected.
     */
    @ParameterizedTest
    @EnumSource(MemberRole.class)
    void requireRoleEnforcesEachRoleExactly(MemberRole role) {
        when(memberRepository.findByOrganizationIdAndUserId(orgId, userId))
                .thenReturn(Optional.of(memberWithRole(role)));

        // Allowed when the member's own role is in the required set.
        permissionService.requireRole(orgId, role);

        MemberRole[] others = java.util.Arrays.stream(MemberRole.values())
                .filter(r -> r != role)
                .toArray(MemberRole[]::new);
        assertThat(catchThrowable(() -> permissionService.requireRole(orgId, others)))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void requireRoleForProjectResolvesOrgThenChecksRole() {
        UUID projectId = UUID.randomUUID();
        Project project = Project.builder()
                .id(projectId)
                .organization(Organization.builder().id(orgId).build())
                .build();
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(memberRepository.findByOrganizationIdAndUserId(orgId, userId))
                .thenReturn(Optional.of(memberWithRole(MemberRole.ADMIN)));

        permissionService.requireRoleForProject(projectId, MemberRole.ADMIN);
    }

    @Test
    void requireRoleForProjectThrowsWhenProjectMissing() {
        UUID projectId = UUID.randomUUID();
        when(projectRepository.findById(projectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> permissionService.requireRoleForProject(projectId, MemberRole.OWNER))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void requireRoleForEnvironmentResolvesProjectAndOrgThenChecksRole() {
        UUID envId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        Project project = Project.builder()
                .id(projectId)
                .organization(Organization.builder().id(orgId).build())
                .build();
        Environment env = Environment.builder().id(envId).project(project).build();
        when(environmentRepository.findById(envId)).thenReturn(Optional.of(env));
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(memberRepository.findByOrganizationIdAndUserId(orgId, userId))
                .thenReturn(Optional.of(memberWithRole(MemberRole.OWNER)));

        permissionService.requireRoleForEnvironment(envId, MemberRole.OWNER, MemberRole.ADMIN);
    }

    @Test
    void requireRoleForEnvironmentThrowsWhenEnvironmentMissing() {
        UUID envId = UUID.randomUUID();
        when(environmentRepository.findById(envId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> permissionService.requireRoleForEnvironment(envId, MemberRole.OWNER))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void isMemberReflectsRepositoryExistence() {
        UUID otherOrg = UUID.randomUUID();
        when(memberRepository.existsByOrganizationIdAndUserId(orgId, userId)).thenReturn(true);
        when(memberRepository.existsByOrganizationIdAndUserId(otherOrg, userId)).thenReturn(false);

        assertThat(permissionService.isMember(orgId)).isTrue();
        assertThat(permissionService.isMember(otherOrg)).isFalse();
    }

    @Test
    void currentUserIdThrowsWhenSecurityContextIsEmpty() {
        // No authentication in the context (filter bypassed / context cleared): the unchecked
        // access must fail loudly rather than silently resolving to a null user.
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> permissionService.currentUserId())
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void currentUserIdThrowsWhenPrincipalIsNotAUserPrincipal() {
        // An SDK ApiKeyAuthenticationToken principal (an Environment) leaking into an admin
        // service call must not be silently accepted as a user.
        SecurityContextHolder.setContext(new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken("not-a-user-principal", null)));

        assertThatThrownBy(() -> permissionService.currentUserId())
                .isInstanceOf(ClassCastException.class);
    }
}
