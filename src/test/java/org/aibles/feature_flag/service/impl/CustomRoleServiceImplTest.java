package org.aibles.feature_flag.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.aibles.feature_flag.domain.entity.CustomRole;
import org.aibles.feature_flag.domain.entity.Organization;
import org.aibles.feature_flag.domain.enums.Action;
import org.aibles.feature_flag.domain.enums.MemberRole;
import org.aibles.feature_flag.dto.request.CreateCustomRoleRequest;
import org.aibles.feature_flag.dto.response.CustomRoleResponse;
import org.aibles.feature_flag.exception.UnauthorizedException;
import org.aibles.feature_flag.repository.CustomRoleRepository;
import org.aibles.feature_flag.repository.OrganizationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The custom-role action ceiling: a ROLE_MANAGE holder cannot define, expand, take over, or delete
 * a role that confers actions beyond their own org-level permissions.
 */
@ExtendWith(MockitoExtension.class)
class CustomRoleServiceImplTest {

  @Mock private CustomRoleRepository customRoleRepository;
  @Mock private OrganizationRepository organizationRepository;
  @Mock private PermissionService permissionService;

  private CustomRoleServiceImpl service;

  private final UUID orgId = UUID.randomUUID();
  private final UUID actorId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    service =
        new CustomRoleServiceImpl(customRoleRepository, organizationRepository, permissionService);
    lenient().when(permissionService.currentUserId()).thenReturn(actorId);
  }

  private void actorHas(MemberRole role) {
    when(permissionService.effectiveActionsForOrg(actorId, orgId))
        .thenReturn(new java.util.HashSet<>(PermissionService.actionsForRole(role)));
  }

  private CreateCustomRoleRequest request(String name, Set<Action> actions) {
    CreateCustomRoleRequest r = new CreateCustomRoleRequest();
    r.setName(name);
    r.setActions(actions);
    return r;
  }

  @Test
  void createRejectsActionsBeyondCreatorsOwn() {
    actorHas(MemberRole.ADMIN); // ADMIN lacks FLAG_DELETE / production toggle
    assertThatThrownBy(
            () ->
                service.create(
                    orgId,
                    request(
                        "Super", Set.of(Action.FLAG_DELETE, Action.FLAG_STATE_UPDATE_PRODUCTION))))
        .isInstanceOf(UnauthorizedException.class)
        .hasMessageContaining("cannot include actions you do not have");
    verify(customRoleRepository, never()).save(any());
  }

  @Test
  void createSucceedsWithinCeiling() {
    actorHas(MemberRole.OWNER);
    when(organizationRepository.findById(orgId))
        .thenReturn(Optional.of(Organization.builder().id(orgId).build()));
    when(customRoleRepository.existsByOrganizationIdAndName(orgId, "Release")).thenReturn(false);
    when(customRoleRepository.save(any(CustomRole.class))).thenAnswer(inv -> inv.getArgument(0));

    CustomRoleResponse resp =
        service.create(
            orgId, request("Release", Set.of(Action.FLAG_STATE_UPDATE, Action.FLAG_READ)));

    assertThat(resp.getName()).isEqualTo("Release");
    assertThat(resp.getActions()).contains(Action.FLAG_STATE_UPDATE);
  }

  @Test
  void updateRejectsExpandingBeyondCeiling() {
    actorHas(MemberRole.ADMIN);
    when(customRoleRepository.findById(any()))
        .thenReturn(
            Optional.of(
                CustomRole.builder()
                    .organization(Organization.builder().id(orgId).build())
                    .actions(new java.util.HashSet<>(Set.of(Action.FLAG_READ)))
                    .build()));

    assertThatThrownBy(
            () ->
                service.update(orgId, UUID.randomUUID(), request("R", Set.of(Action.FLAG_DELETE))))
        .isInstanceOf(UnauthorizedException.class);
    verify(customRoleRepository, never()).save(any());
  }

  @Test
  void updateRejectsTakeoverOfHigherRole() {
    actorHas(MemberRole.ADMIN);
    // Existing role already confers FLAG_DELETE (authored by an OWNER); ADMIN cannot edit it.
    when(customRoleRepository.findById(any()))
        .thenReturn(
            Optional.of(
                CustomRole.builder()
                    .organization(Organization.builder().id(orgId).build())
                    .actions(new java.util.HashSet<>(Set.of(Action.FLAG_DELETE)))
                    .build()));

    assertThatThrownBy(
            () -> service.update(orgId, UUID.randomUUID(), request("R", Set.of(Action.FLAG_READ))))
        .isInstanceOf(UnauthorizedException.class);
  }

  @Test
  void deleteRejectsRoleConferringMoreThanDeleterHolds() {
    actorHas(MemberRole.ADMIN);
    when(customRoleRepository.findById(any()))
        .thenReturn(
            Optional.of(
                CustomRole.builder()
                    .organization(Organization.builder().id(orgId).build())
                    .actions(new java.util.HashSet<>(Set.of(Action.FLAG_DELETE)))
                    .build()));

    assertThatThrownBy(() -> service.delete(orgId, UUID.randomUUID()))
        .isInstanceOf(UnauthorizedException.class);
    verify(customRoleRepository, never()).delete(any());
  }
}
