package org.aibles.feature_flag.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.aibles.feature_flag.domain.entity.CustomRole;
import org.aibles.feature_flag.domain.entity.Organization;
import org.aibles.feature_flag.domain.entity.PermissionGrant;
import org.aibles.feature_flag.domain.entity.Project;
import org.aibles.feature_flag.domain.entity.User;
import org.aibles.feature_flag.domain.enums.AuditAction;
import org.aibles.feature_flag.domain.enums.AuditEntityType;
import org.aibles.feature_flag.domain.enums.MemberRole;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Guards on the grant-management endpoints: the subset ceiling (no escalation) and the
 * org-membership requirement (tenant isolation), for both built-in and custom-role grants.
 */
@ExtendWith(MockitoExtension.class)
class ProjectGrantServiceImplTest {

  @Mock private PermissionGrantRepository grantRepository;
  @Mock private ProjectRepository projectRepository;
  @Mock private OrganizationMemberRepository memberRepository;
  @Mock private CustomRoleRepository customRoleRepository;
  @Mock private UserRepository userRepository;
  @Mock private PermissionService permissionService;
  @Mock private AuditService auditService;

  private ProjectGrantServiceImpl service;

  private final UUID projectId = UUID.randomUUID();
  private final UUID orgId = UUID.randomUUID();
  private final UUID granterId = UUID.randomUUID();
  private final UUID targetUserId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    service =
        new ProjectGrantServiceImpl(
            grantRepository,
            projectRepository,
            memberRepository,
            customRoleRepository,
            userRepository,
            permissionService,
            auditService);
    lenient().when(permissionService.currentUserId()).thenReturn(granterId);
    lenient()
        .when(projectRepository.findById(projectId))
        .thenReturn(
            Optional.of(
                Project.builder()
                    .id(projectId)
                    .organization(Organization.builder().id(orgId).build())
                    .build()));
  }

  private CreateProjectGrantRequest builtInRequest(MemberRole role) {
    CreateProjectGrantRequest r = new CreateProjectGrantRequest();
    r.setUserId(targetUserId);
    r.setRole(role);
    return r;
  }

  private void granterHas(MemberRole role) {
    when(permissionService.effectiveActionsForProject(granterId, projectId))
        .thenReturn(new java.util.HashSet<>(PermissionService.actionsForRole(role)));
  }

  @Test
  void upsertBlocksGrantingBeyondOwnPermissions() {
    // Granter is only ADMIN; granting OWNER (which adds FLAG_DELETE etc.) is beyond their set.
    granterHas(MemberRole.ADMIN);

    assertThatThrownBy(() -> service.upsertGrant(projectId, builtInRequest(MemberRole.OWNER)))
        .isInstanceOf(UnauthorizedException.class)
        .hasMessageContaining("beyond your own");

    verify(grantRepository, never()).save(any());
  }

  @Test
  void upsertRejectsWhenNeitherRoleNorCustomRoleProvided() {
    CreateProjectGrantRequest r = new CreateProjectGrantRequest();
    r.setUserId(targetUserId);

    assertThatThrownBy(() -> service.upsertGrant(projectId, r))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exactly one");
  }

  @Test
  void upsertRequiresTargetToBeOrgMember() {
    granterHas(MemberRole.OWNER);
    when(userRepository.findById(targetUserId))
        .thenReturn(Optional.of(User.builder().id(targetUserId).build()));
    when(memberRepository.existsByOrganizationIdAndUserId(orgId, targetUserId)).thenReturn(false);

    assertThatThrownBy(() -> service.upsertGrant(projectId, builtInRequest(MemberRole.ADMIN)))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining("not a member");

    verify(grantRepository, never()).save(any());
  }

  @Test
  void upsertCreatesBuiltInGrantForOrgMemberWithinCeiling() {
    granterHas(MemberRole.OWNER);
    User target = User.builder().id(targetUserId).email("t@example.com").build();
    when(userRepository.findById(targetUserId)).thenReturn(Optional.of(target));
    when(memberRepository.existsByOrganizationIdAndUserId(orgId, targetUserId)).thenReturn(true);
    when(grantRepository.findByUser_IdAndScopeTypeAndScopeId(
            targetUserId, ScopeType.PROJECT, projectId))
        .thenReturn(Optional.empty());
    when(grantRepository.save(any(PermissionGrant.class))).thenAnswer(inv -> inv.getArgument(0));

    ProjectGrantResponse response =
        service.upsertGrant(projectId, builtInRequest(MemberRole.ADMIN));

    assertThat(response.getUserId()).isEqualTo(targetUserId);
    assertThat(response.getRole()).isEqualTo(MemberRole.ADMIN);
    verify(grantRepository).save(any(PermissionGrant.class));
  }

  @Test
  void upsertRejectsCustomRoleFromAnotherOrg() {
    // The foreign-org check fires before the ceiling, so no granter stub is needed.
    UUID customRoleId = UUID.randomUUID();
    CreateProjectGrantRequest r = new CreateProjectGrantRequest();
    r.setUserId(targetUserId);
    r.setCustomRoleId(customRoleId);
    CustomRole foreign =
        CustomRole.builder()
            .id(customRoleId)
            .organization(Organization.builder().id(UUID.randomUUID()).build())
            .build();
    when(customRoleRepository.findById(customRoleId)).thenReturn(Optional.of(foreign));

    assertThatThrownBy(() -> service.upsertGrant(projectId, r))
        .isInstanceOf(ResourceNotFoundException.class);

    verify(grantRepository, never()).save(any());
  }

  @Test
  void revokeBlocksRemovingGrantBeyondOwnPermissions() {
    when(grantRepository.findByUser_IdAndScopeTypeAndScopeId(
            targetUserId, ScopeType.PROJECT, projectId))
        .thenReturn(Optional.of(PermissionGrant.builder().role(MemberRole.OWNER).build()));
    granterHas(MemberRole.ADMIN);

    assertThatThrownBy(() -> service.revokeGrant(projectId, targetUserId))
        .isInstanceOf(UnauthorizedException.class)
        .hasMessageContaining("beyond your own");

    verify(grantRepository, never()).delete(any());
  }

  // --- Audit trail on permission changes ---

  @Test
  void grantIsAudited() {
    granterHas(MemberRole.OWNER);
    when(userRepository.findById(targetUserId))
        .thenReturn(Optional.of(User.builder().id(targetUserId).email("t@example.com").build()));
    when(memberRepository.existsByOrganizationIdAndUserId(orgId, targetUserId)).thenReturn(true);
    when(grantRepository.findByUser_IdAndScopeTypeAndScopeId(
            targetUserId, ScopeType.PROJECT, projectId))
        .thenReturn(Optional.empty());
    UUID grantId = UUID.randomUUID();
    when(grantRepository.save(any(PermissionGrant.class)))
        .thenAnswer(
            inv -> {
              PermissionGrant g = inv.getArgument(0);
              g.setId(grantId);
              return g;
            });

    ProjectGrantResponse resp = service.upsertGrant(projectId, builtInRequest(MemberRole.ADMIN));

    // A brand-new grant has no before-state.
    verify(auditService)
        .record(
            eq(AuditEntityType.PERMISSION_GRANT),
            eq(grantId),
            eq(AuditAction.GRANT_PERMISSION),
            eq(orgId),
            isNull(),
            eq(resp));
  }

  @Test
  void revokeIsAuditedWithTheRevokedGrantAsBeforeState() {
    granterHas(MemberRole.OWNER);
    UUID grantId = UUID.randomUUID();
    PermissionGrant grant =
        PermissionGrant.builder()
            .id(grantId)
            .user(User.builder().id(targetUserId).email("t@example.com").build())
            .scopeType(ScopeType.PROJECT)
            .scopeId(projectId)
            .role(MemberRole.ADMIN)
            .build();
    when(grantRepository.findByUser_IdAndScopeTypeAndScopeId(
            targetUserId, ScopeType.PROJECT, projectId))
        .thenReturn(Optional.of(grant));

    service.revokeGrant(projectId, targetUserId);

    verify(grantRepository).delete(grant);
    verify(auditService)
        .record(
            eq(AuditEntityType.PERMISSION_GRANT),
            eq(grantId),
            eq(AuditAction.REVOKE_PERMISSION),
            eq(orgId),
            any(ProjectGrantResponse.class),
            isNull());
  }
}
