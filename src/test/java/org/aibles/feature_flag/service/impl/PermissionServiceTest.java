package org.aibles.feature_flag.service.impl;

import org.aibles.feature_flag.domain.entity.CustomRole;
import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.domain.entity.Organization;
import org.aibles.feature_flag.domain.entity.OrganizationMember;
import org.aibles.feature_flag.domain.entity.PermissionGrant;
import org.aibles.feature_flag.domain.entity.Project;
import org.aibles.feature_flag.domain.entity.User;
import org.aibles.feature_flag.domain.enums.Action;
import org.aibles.feature_flag.domain.enums.EnvType;
import org.aibles.feature_flag.domain.enums.MemberRole;
import org.aibles.feature_flag.domain.enums.ScopeType;
import org.aibles.feature_flag.exception.UnauthorizedException;
import org.aibles.feature_flag.repository.EnvironmentRepository;
import org.aibles.feature_flag.repository.OrganizationMemberRepository;
import org.aibles.feature_flag.repository.PermissionGrantRepository;
import org.aibles.feature_flag.repository.ProjectRepository;
import org.aibles.feature_flag.security.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the action-set based PDP: effective-action resolution (org role ∪ grant),
 * built-in/custom roles, the production-capability rule (B/C), and the change-window rule (D).
 * The clock is fixed at 10:00 so window tests are deterministic.
 */
@ExtendWith(MockitoExtension.class)
class PermissionServiceTest {

    @Mock private OrganizationMemberRepository memberRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private EnvironmentRepository environmentRepository;
    @Mock private PermissionGrantRepository grantRepository;

    private PermissionService permissionService;

    private final UUID userId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();

    // Fixed at 2026-07-02T10:30Z → local hour 10 (UTC zone).
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-02T10:30:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        permissionService = new PermissionService(
                memberRepository, projectRepository, environmentRepository, grantRepository, clock);
        lenient().when(grantRepository.findByUser_IdAndScopeTypeAndScopeId(any(), any(), any()))
                .thenReturn(Optional.empty());
        UserPrincipal principal = UserPrincipal.from(User.builder()
                .id(userId).email("member@example.com").passwordHash("x").build());
        SecurityContextHolder.setContext(new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(principal, null)));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── Subject plumbing ────────────────────────────────────────────────────────────────

    @Test
    void currentUserIdReadsPrincipalFromSecurityContext() {
        assertThat(permissionService.currentUserId()).isEqualTo(userId);
    }

    @Test
    void currentUserIdThrowsWhenSecurityContextIsEmpty() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> permissionService.currentUserId()).isInstanceOf(NullPointerException.class);
    }

    @Test
    void currentUserIdThrowsWhenPrincipalIsNotAUserPrincipal() {
        SecurityContextHolder.setContext(new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken("not-a-user-principal", null)));
        assertThatThrownBy(() -> permissionService.currentUserId()).isInstanceOf(ClassCastException.class);
    }

    @Test
    void isMemberReflectsRepositoryExistence() {
        UUID other = UUID.randomUUID();
        when(memberRepository.existsByOrganizationIdAndUserId(orgId, userId)).thenReturn(true);
        when(memberRepository.existsByOrganizationIdAndUserId(other, userId)).thenReturn(false);
        assertThat(permissionService.isMember(orgId)).isTrue();
        assertThat(permissionService.isMember(other)).isFalse();
    }

    // ── Role → action matrix ────────────────────────────────────────────────────────────

    @Test
    void actionMatrixEncodesRoleCapabilities() {
        assertThat(PermissionService.actionsForRole(MemberRole.VIEWER))
                .containsExactlyInAnyOrder(Action.FLAG_READ, Action.ENV_READ, Action.PROJECT_READ);

        assertThat(PermissionService.actionsForRole(MemberRole.ADMIN))
                .contains(Action.FLAG_STATE_UPDATE, Action.GRANT_MANAGE, Action.ROLE_MANAGE)
                .doesNotContain(Action.FLAG_STATE_UPDATE_PRODUCTION, Action.FLAG_DELETE, Action.ORG_DELETE);

        assertThat(PermissionService.actionsForRole(MemberRole.OWNER))
                .contains(Action.FLAG_STATE_UPDATE_PRODUCTION, Action.FLAG_DELETE, Action.ORG_DELETE);

        assertThat(PermissionService.actionsForRole(null)).isEmpty();
    }

    // ── Effective actions (org role ∪ grant) ────────────────────────────────────────────

    @Test
    void effectiveActionsForOrgReflectsMembership() {
        when(memberRepository.findByOrganizationIdAndUserId(orgId, userId))
                .thenReturn(Optional.of(member(MemberRole.ADMIN)));
        assertThat(permissionService.effectiveActionsForOrg(userId, orgId))
                .isEqualTo(PermissionService.actionsForRole(MemberRole.ADMIN));

        when(memberRepository.findByOrganizationIdAndUserId(orgId, userId)).thenReturn(Optional.empty());
        assertThat(permissionService.effectiveActionsForOrg(userId, orgId)).isEmpty();
    }

    @Test
    void projectGrantUnionsWithOrgRole() {
        stubProject();
        when(memberRepository.findByOrganizationIdAndUserId(orgId, userId))
                .thenReturn(Optional.of(member(MemberRole.VIEWER)));
        when(grantRepository.findByUser_IdAndScopeTypeAndScopeId(userId, ScopeType.PROJECT, projectId))
                .thenReturn(Optional.of(PermissionGrant.builder().role(MemberRole.ADMIN).build()));

        assertThat(permissionService.effectiveActionsForProject(userId, projectId))
                .contains(Action.FLAG_STATE_UPDATE, Action.PROJECT_READ);
    }

    @Test
    void customRoleGrantContributesItsActionsToNonMember() {
        stubProject();
        when(memberRepository.findByOrganizationIdAndUserId(orgId, userId)).thenReturn(Optional.empty());
        CustomRole releaseManager = CustomRole.builder()
                .actions(Set.of(Action.FLAG_STATE_UPDATE, Action.FLAG_READ)).build();
        when(grantRepository.findByUser_IdAndScopeTypeAndScopeId(userId, ScopeType.PROJECT, projectId))
                .thenReturn(Optional.of(PermissionGrant.builder().customRole(releaseManager).build()));

        assertThat(permissionService.effectiveActionsForProject(userId, projectId))
                .containsExactlyInAnyOrder(Action.FLAG_STATE_UPDATE, Action.FLAG_READ);
    }

    // ── check(): action gate ────────────────────────────────────────────────────────────

    @Test
    void checkAllowsReadForViewer() {
        stubProjectRole(MemberRole.VIEWER);
        assertThatCode(() -> permissionService.check(Action.FLAG_READ, PermissionService.ResourceRef.project(projectId)))
                .doesNotThrowAnyException();
    }

    @Test
    void checkDeniesActionOutsideRoleSet() {
        stubProjectRole(MemberRole.VIEWER);
        assertThatThrownBy(() -> permissionService.check(Action.FLAG_DELETE, PermissionService.ResourceRef.project(projectId)))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("Insufficient permissions for action");
    }

    @Test
    void checkOrgScopeDeniesOrgDeleteForAdminAllowsForOwner() {
        when(memberRepository.findByOrganizationIdAndUserId(orgId, userId))
                .thenReturn(Optional.of(member(MemberRole.ADMIN)));
        assertThatThrownBy(() -> permissionService.check(Action.ORG_DELETE, PermissionService.ResourceRef.org(orgId)))
                .isInstanceOf(UnauthorizedException.class);

        when(memberRepository.findByOrganizationIdAndUserId(orgId, userId))
                .thenReturn(Optional.of(member(MemberRole.OWNER)));
        assertThatCode(() -> permissionService.check(Action.ORG_DELETE, PermissionService.ResourceRef.org(orgId)))
                .doesNotThrowAnyException();
    }

    // ── check(): production capability (B/C) ─────────────────────────────────────────────

    @Test
    void adminCanToggleStagingStateButNotProduction() {
        stubProjectRole(MemberRole.ADMIN);
        assertThatCode(() -> permissionService.check(Action.FLAG_STATE_UPDATE, env(EnvType.STAGING, null, null)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> permissionService.check(Action.FLAG_STATE_UPDATE, env(EnvType.PRODUCTION, null, null)))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("PRODUCTION");
    }

    @Test
    void ownerCanToggleProductionState() {
        stubProjectRole(MemberRole.OWNER);
        assertThatCode(() -> permissionService.check(Action.FLAG_STATE_UPDATE, env(EnvType.PRODUCTION, null, null)))
                .doesNotThrowAnyException();
    }

    @Test
    void customRoleWithProductionCapabilityCanToggleProduction() {
        stubProject();
        when(memberRepository.findByOrganizationIdAndUserId(orgId, userId)).thenReturn(Optional.empty());
        CustomRole prodToggler = CustomRole.builder()
                .actions(Set.of(Action.FLAG_STATE_UPDATE, Action.FLAG_STATE_UPDATE_PRODUCTION)).build();
        when(grantRepository.findByUser_IdAndScopeTypeAndScopeId(userId, ScopeType.PROJECT, projectId))
                .thenReturn(Optional.of(PermissionGrant.builder().customRole(prodToggler).build()));

        assertThatCode(() -> permissionService.check(Action.FLAG_STATE_UPDATE, env(EnvType.PRODUCTION, null, null)))
                .doesNotThrowAnyException();
    }

    // ── check(): change window (D) ───────────────────────────────────────────────────────

    @Test
    void ownerBlockedOutsideProductionChangeWindow() {
        stubProjectRole(MemberRole.OWNER);
        // Window 13–17; clock is 10 → outside.
        assertThatThrownBy(() -> permissionService.check(Action.FLAG_STATE_UPDATE, env(EnvType.PRODUCTION, 13, 17)))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("change window");
    }

    @Test
    void ownerAllowedInsideProductionChangeWindow() {
        stubProjectRole(MemberRole.OWNER);
        // Window 9–12; clock is 10 → inside.
        assertThatCode(() -> permissionService.check(Action.FLAG_STATE_UPDATE, env(EnvType.PRODUCTION, 9, 12)))
                .doesNotThrowAnyException();
    }

    @Test
    void zeroWidthChangeWindowIsTreatedAsNoRestriction() {
        stubProjectRole(MemberRole.OWNER);
        // start == end → no restriction, not a permanent lockout.
        assertThatCode(() -> permissionService.check(Action.FLAG_STATE_UPDATE, env(EnvType.PRODUCTION, 10, 10)))
                .doesNotThrowAnyException();
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────

    private OrganizationMember member(MemberRole role) {
        return OrganizationMember.builder().role(role).build();
    }

    private void stubProject() {
        Project project = Project.builder().id(projectId)
                .organization(Organization.builder().id(orgId).build()).build();
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
    }

    private void stubProjectRole(MemberRole role) {
        stubProject();
        when(memberRepository.findByOrganizationIdAndUserId(orgId, userId)).thenReturn(Optional.of(member(role)));
    }

    private PermissionService.ResourceRef env(EnvType type, Integer start, Integer end) {
        Project project = Project.builder().id(projectId)
                .organization(Organization.builder().id(orgId).build()).build();
        Environment environment = Environment.builder()
                .id(UUID.randomUUID()).project(project).type(type)
                .changeWindowStartHour(start).changeWindowEndHour(end).build();
        return PermissionService.ResourceRef.environment(projectId, environment);
    }
}
