package org.aibles.feature_flag.service.impl;

import org.aibles.feature_flag.domain.entity.OrganizationMember;
import org.aibles.feature_flag.domain.entity.Project;
import org.aibles.feature_flag.domain.enums.MemberRole;
import org.aibles.feature_flag.domain.enums.ScopeType;
import org.aibles.feature_flag.dto.request.InviteMemberRequest;
import org.aibles.feature_flag.exception.UnauthorizedException;
import org.aibles.feature_flag.repository.OrganizationMemberRepository;
import org.aibles.feature_flag.repository.OrganizationRepository;
import org.aibles.feature_flag.repository.PermissionGrantRepository;
import org.aibles.feature_flag.repository.ProjectRepository;
import org.aibles.feature_flag.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrganizationServiceImplTest {

    @Mock private OrganizationRepository organizationRepository;
    @Mock private OrganizationMemberRepository memberRepository;
    @Mock private UserRepository userRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private PermissionGrantRepository grantRepository;
    @Mock private PermissionService permissionService;

    private OrganizationServiceImpl service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final UUID targetId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new OrganizationServiceImpl(organizationRepository, memberRepository, userRepository,
                projectRepository, grantRepository, permissionService);
        lenient().when(permissionService.currentUserId()).thenReturn(actorId);
    }

    @Test
    void inviteMemberCannotGrantRoleHigherThanOwn() {
        when(permissionService.effectiveActionsForOrg(actorId, orgId))
                .thenReturn(new java.util.HashSet<>(PermissionService.actionsForRole(MemberRole.ADMIN)));
        InviteMemberRequest req = new InviteMemberRequest();
        req.setUserId(targetId);
        req.setRole(MemberRole.OWNER);

        assertThatThrownBy(() -> service.inviteMember(orgId, req))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("higher than your own");

        verify(memberRepository, never()).save(any());
    }

    @Test
    void removeMemberRevokesProjectGrantsInOrg() {
        OrganizationMember member = OrganizationMember.builder().role(MemberRole.VIEWER).build();
        when(memberRepository.findByOrganizationIdAndUserId(orgId, targetId)).thenReturn(Optional.of(member));
        UUID projectId = UUID.randomUUID();
        when(projectRepository.findAllByOrganizationId(orgId))
                .thenReturn(List.of(Project.builder().id(projectId).build()));

        service.removeMember(orgId, targetId);

        verify(memberRepository).delete(member);
        verify(grantRepository).deleteByUser_IdAndScopeTypeAndScopeIdIn(
                eq(targetId), eq(ScopeType.PROJECT), eq(List.of(projectId)));
    }
}
