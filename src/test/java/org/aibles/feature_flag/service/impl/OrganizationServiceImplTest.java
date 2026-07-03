package org.aibles.feature_flag.service.impl;

import org.aibles.feature_flag.domain.entity.Organization;
import org.aibles.feature_flag.domain.entity.OrganizationMember;
import org.aibles.feature_flag.domain.entity.User;
import org.aibles.feature_flag.domain.enums.MemberRole;
import org.aibles.feature_flag.dto.request.CreateOrganizationRequest;
import org.aibles.feature_flag.dto.request.InviteMemberRequest;
import org.aibles.feature_flag.dto.request.UpdateOrganizationRequest;
import org.aibles.feature_flag.dto.response.MemberResponse;
import org.aibles.feature_flag.dto.response.OrganizationResponse;
import org.aibles.feature_flag.exception.DuplicateResourceException;
import org.aibles.feature_flag.exception.ResourceNotFoundException;
import org.aibles.feature_flag.exception.UnauthorizedException;
import org.aibles.feature_flag.repository.OrganizationMemberRepository;
import org.aibles.feature_flag.repository.OrganizationRepository;
import org.aibles.feature_flag.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrganizationServiceImplTest {

    @Mock
    private OrganizationRepository organizationRepository;
    @Mock
    private OrganizationMemberRepository memberRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private PermissionService permissionService;

    private OrganizationServiceImpl service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new OrganizationServiceImpl(organizationRepository, memberRepository, userRepository, permissionService);
    }

    private Organization existingOrg() {
        return Organization.builder().id(orgId).name("Acme").slug("acme").build();
    }

    private User user(UUID id) {
        return User.builder().id(id).email("u@example.com").passwordHash("x")
                .firstName("First").lastName("Last").build();
    }

    @Test
    void createMakesCreatorTheOwner() {
        CreateOrganizationRequest request = new CreateOrganizationRequest();
        request.setName("Acme");
        request.setSlug("acme");
        when(organizationRepository.existsBySlug("acme")).thenReturn(false);
        when(organizationRepository.save(any(Organization.class))).thenAnswer(inv -> inv.getArgument(0));
        when(permissionService.currentUserId()).thenReturn(userId);
        when(userRepository.getReferenceById(userId)).thenReturn(user(userId));

        OrganizationResponse response = service.create(request);

        assertThat(response.getSlug()).isEqualTo("acme");

        ArgumentCaptor<OrganizationMember> member = ArgumentCaptor.forClass(OrganizationMember.class);
        verify(memberRepository).save(member.capture());
        assertThat(member.getValue().getRole()).isEqualTo(MemberRole.OWNER);
        assertThat(member.getValue().getUser().getId()).isEqualTo(userId);
    }

    @Test
    void createRejectsDuplicateSlug() {
        CreateOrganizationRequest request = new CreateOrganizationRequest();
        request.setName("Acme");
        request.setSlug("acme");
        when(organizationRepository.existsBySlug("acme")).thenReturn(true);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(DuplicateResourceException.class);

        verify(organizationRepository, never()).save(any());
    }

    @Test
    void getReturnsOrgWhenCallerIsMember() {
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(existingOrg()));
        when(permissionService.isMember(orgId)).thenReturn(true);

        assertThat(service.get(orgId).getId()).isEqualTo(orgId);
    }

    @Test
    void getRejectsNonMember() {
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(existingOrg()));
        when(permissionService.isMember(orgId)).thenReturn(false);

        assertThatThrownBy(() -> service.get(orgId))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void listMembersRejectsNonMember() {
        when(permissionService.isMember(orgId)).thenReturn(false);

        assertThatThrownBy(() -> service.listMembers(orgId))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void inviteMemberAddsUserWithRequestedRole() {
        UUID inviteeId = UUID.randomUUID();
        InviteMemberRequest request = new InviteMemberRequest();
        request.setUserId(inviteeId);
        request.setRole(MemberRole.VIEWER);
        when(memberRepository.existsByOrganizationIdAndUserId(orgId, inviteeId)).thenReturn(false);
        when(userRepository.findById(inviteeId)).thenReturn(Optional.of(user(inviteeId)));
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(existingOrg()));

        MemberResponse response = service.inviteMember(orgId, request);

        assertThat(response.getUserId()).isEqualTo(inviteeId);
        assertThat(response.getRole()).isEqualTo(MemberRole.VIEWER);

        ArgumentCaptor<OrganizationMember> saved = ArgumentCaptor.forClass(OrganizationMember.class);
        verify(memberRepository).save(saved.capture());
        assertThat(saved.getValue().getRole()).isEqualTo(MemberRole.VIEWER);
        assertThat(saved.getValue().getUser().getId()).isEqualTo(inviteeId);
    }

    @Test
    void inviteMemberRejectsExistingMember() {
        UUID inviteeId = UUID.randomUUID();
        InviteMemberRequest request = new InviteMemberRequest();
        request.setUserId(inviteeId);
        request.setRole(MemberRole.VIEWER);
        when(memberRepository.existsByOrganizationIdAndUserId(orgId, inviteeId)).thenReturn(true);

        assertThatThrownBy(() -> service.inviteMember(orgId, request))
                .isInstanceOf(DuplicateResourceException.class);

        verify(memberRepository, never()).save(any());
    }

    @Test
    void inviteMemberThrowsWhenUserMissing() {
        UUID inviteeId = UUID.randomUUID();
        InviteMemberRequest request = new InviteMemberRequest();
        request.setUserId(inviteeId);
        request.setRole(MemberRole.VIEWER);
        when(memberRepository.existsByOrganizationIdAndUserId(orgId, inviteeId)).thenReturn(false);
        when(userRepository.findById(inviteeId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.inviteMember(orgId, request))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void removeMemberDeletesNonLastOwner() {
        OrganizationMember member = OrganizationMember.builder()
                .role(MemberRole.OWNER).user(user(userId)).build();
        when(memberRepository.findByOrganizationIdAndUserId(orgId, userId)).thenReturn(Optional.of(member));
        when(memberRepository.countByOrganizationIdAndRole(orgId, MemberRole.OWNER)).thenReturn(2L);

        service.removeMember(orgId, userId);

        verify(memberRepository).delete(member);
    }

    @Test
    void removeMemberRejectsRemovingTheOnlyOwner() {
        OrganizationMember member = OrganizationMember.builder()
                .role(MemberRole.OWNER).user(user(userId)).build();
        when(memberRepository.findByOrganizationIdAndUserId(orgId, userId)).thenReturn(Optional.of(member));
        when(memberRepository.countByOrganizationIdAndRole(orgId, MemberRole.OWNER)).thenReturn(1L);

        assertThatThrownBy(() -> service.removeMember(orgId, userId))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("only OWNER");

        verify(memberRepository, never()).delete(any());
    }

    @Test
    void removeMemberAllowsRemovingNonOwnerRegardlessOfOwnerCount() {
        OrganizationMember member = OrganizationMember.builder()
                .role(MemberRole.VIEWER).user(user(userId)).build();
        when(memberRepository.findByOrganizationIdAndUserId(orgId, userId)).thenReturn(Optional.of(member));

        service.removeMember(orgId, userId);

        verify(memberRepository).delete(member);
    }

    @Test
    void removeMemberThrowsWhenMemberMissing() {
        when(memberRepository.findByOrganizationIdAndUserId(orgId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.removeMember(orgId, userId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateChangesNameOnly() {
        Organization org = existingOrg();
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
        when(organizationRepository.save(any(Organization.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateOrganizationRequest request = new UpdateOrganizationRequest();
        request.setName("Acme Corp");

        OrganizationResponse response = service.update(orgId, request);

        assertThat(response.getName()).isEqualTo("Acme Corp");
        assertThat(response.getSlug()).isEqualTo("acme");
    }

    @Test
    void listMineResolvesOrgsForCurrentUser() {
        when(permissionService.currentUserId()).thenReturn(userId);
        when(memberRepository.findOrganizationIdsByUserId(userId)).thenReturn(List.of(orgId));
        when(organizationRepository.findAllById(List.of(orgId))).thenReturn(List.of(existingOrg()));

        List<OrganizationResponse> result = service.listMine();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(orgId);
    }
}
