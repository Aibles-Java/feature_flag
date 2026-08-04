package org.aibles.feature_flag.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.aibles.feature_flag.domain.entity.Organization;
import org.aibles.feature_flag.domain.entity.OrganizationMember;
import org.aibles.feature_flag.domain.entity.User;
import org.aibles.feature_flag.domain.enums.MemberRole;
import org.aibles.feature_flag.dto.request.CreateOrganizationRequest;
import org.aibles.feature_flag.dto.request.InviteMemberRequest;
import org.aibles.feature_flag.dto.request.UpdateOrganizationRequest;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrganizationServiceImplTest {

  @Mock OrganizationRepository organizationRepository;
  @Mock OrganizationMemberRepository memberRepository;
  @Mock UserRepository userRepository;
  @Mock PermissionService permissionService;

  OrganizationServiceImpl service;

  UUID userId = UUID.randomUUID();
  UUID orgId = UUID.randomUUID();
  Organization org;

  @BeforeEach
  void setUp() {
    service =
        new OrganizationServiceImpl(
            organizationRepository, memberRepository, userRepository, permissionService);
    org = Organization.builder().id(orgId).name("Acme").slug("acme").build();
    doNothing().when(permissionService).requireRole(any(), any(MemberRole[].class));
  }

  @Test
  void create_throwsDuplicate_whenSlugAlreadyTaken() {
    when(organizationRepository.existsBySlug("acme")).thenReturn(true);

    CreateOrganizationRequest req = new CreateOrganizationRequest();
    req.setName("Acme");
    req.setSlug("acme");

    assertThatThrownBy(() -> service.create(req))
        .isInstanceOf(DuplicateResourceException.class)
        .hasMessageContaining("acme");
    verify(organizationRepository, never()).save(any());
  }

  @Test
  void create_addsCurrentUserAsOwner() {
    when(organizationRepository.existsBySlug("neworg")).thenReturn(false);
    when(organizationRepository.save(any())).thenReturn(org);
    when(permissionService.currentUserId()).thenReturn(userId);
    User user = User.builder().id(userId).email("owner@example.com").passwordHash("x").build();
    when(userRepository.getReferenceById(userId)).thenReturn(user);
    when(memberRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    CreateOrganizationRequest req = new CreateOrganizationRequest();
    req.setName("NewOrg");
    req.setSlug("neworg");

    service.create(req);

    ArgumentCaptor<OrganizationMember> captor = ArgumentCaptor.forClass(OrganizationMember.class);
    verify(memberRepository).save(captor.capture());
    assertThat(captor.getValue().getRole()).isEqualTo(MemberRole.OWNER);
    assertThat(captor.getValue().getUser().getId()).isEqualTo(userId);
  }

  @Test
  void listMine_returnsOrgsTheUserBelongsTo() {
    when(permissionService.currentUserId()).thenReturn(userId);
    when(memberRepository.findOrganizationIdsByUserId(userId)).thenReturn(List.of(orgId));
    when(organizationRepository.findByIdIn(eq(List.of(orgId)), any()))
        .thenReturn(new PageImpl<>(List.of(org)));

    Page<OrganizationResponse> result = service.listMine(PageRequest.of(0, 20));

    assertThat(result.getContent()).hasSize(1);
    assertThat(result.getContent().get(0).getSlug()).isEqualTo("acme");
  }

  @Test
  void get_throwsUnauthorized_whenCallerIsNotMember() {
    when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
    when(permissionService.isMember(orgId)).thenReturn(false);

    assertThatThrownBy(() -> service.get(orgId)).isInstanceOf(UnauthorizedException.class);
  }

  @Test
  void inviteMember_throwsDuplicate_whenUserAlreadyMember() {
    UUID newUserId = UUID.randomUUID();
    when(memberRepository.existsByOrganizationIdAndUserId(orgId, newUserId)).thenReturn(true);

    InviteMemberRequest req = new InviteMemberRequest();
    req.setUserId(newUserId);
    req.setRole(MemberRole.VIEWER);

    assertThatThrownBy(() -> service.inviteMember(orgId, req))
        .isInstanceOf(DuplicateResourceException.class);
  }

  @Test
  void inviteMember_throwsResourceNotFound_whenUserDoesNotExist() {
    UUID newUserId = UUID.randomUUID();
    when(memberRepository.existsByOrganizationIdAndUserId(orgId, newUserId)).thenReturn(false);
    when(userRepository.findById(newUserId)).thenReturn(Optional.empty());

    InviteMemberRequest req = new InviteMemberRequest();
    req.setUserId(newUserId);
    req.setRole(MemberRole.ADMIN);

    assertThatThrownBy(() -> service.inviteMember(orgId, req))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  void removeMember_throwsUnauthorized_whenRemovingLastOwner() {
    UUID targetUserId = UUID.randomUUID();
    OrganizationMember ownerMember =
        OrganizationMember.builder()
            .organization(org)
            .user(User.builder().id(targetUserId).email("o@e.com").passwordHash("x").build())
            .role(MemberRole.OWNER)
            .build();

    when(memberRepository.findByOrganizationIdAndUserId(orgId, targetUserId))
        .thenReturn(Optional.of(ownerMember));
    when(memberRepository.countByOrganizationIdAndRole(orgId, MemberRole.OWNER)).thenReturn(1L);

    assertThatThrownBy(() -> service.removeMember(orgId, targetUserId))
        .isInstanceOf(UnauthorizedException.class);
    verify(memberRepository, never()).delete(any());
  }

  @Test
  void removeMember_succeeds_whenMultipleOwnersExist() {
    UUID targetUserId = UUID.randomUUID();
    OrganizationMember ownerMember =
        OrganizationMember.builder()
            .organization(org)
            .user(User.builder().id(targetUserId).email("o@e.com").passwordHash("x").build())
            .role(MemberRole.OWNER)
            .build();

    when(memberRepository.findByOrganizationIdAndUserId(orgId, targetUserId))
        .thenReturn(Optional.of(ownerMember));
    when(memberRepository.countByOrganizationIdAndRole(orgId, MemberRole.OWNER)).thenReturn(2L);

    service.removeMember(orgId, targetUserId);

    verify(memberRepository).delete(ownerMember);
  }

  @Test
  void update_changesName_whenProvided() {
    when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
    when(organizationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

    UpdateOrganizationRequest req = new UpdateOrganizationRequest();
    req.setName("New Name");

    OrganizationResponse result = service.update(orgId, req);

    assertThat(result.getName()).isEqualTo("New Name");
  }

  @Test
  void listMembers_throwsUnauthorized_whenCallerIsNotMember() {
    when(permissionService.isMember(orgId)).thenReturn(false);

    assertThatThrownBy(() -> service.listMembers(orgId, Pageable.unpaged()))
        .isInstanceOf(UnauthorizedException.class);
  }
}
