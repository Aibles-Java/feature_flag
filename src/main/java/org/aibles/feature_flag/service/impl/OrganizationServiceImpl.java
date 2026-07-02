package org.aibles.feature_flag.service.impl;

import lombok.RequiredArgsConstructor;
import org.aibles.feature_flag.domain.entity.Organization;
import org.aibles.feature_flag.domain.entity.OrganizationMember;
import org.aibles.feature_flag.domain.entity.User;
import org.aibles.feature_flag.domain.entity.Project;
import org.aibles.feature_flag.domain.enums.Action;
import org.aibles.feature_flag.domain.enums.MemberRole;
import org.aibles.feature_flag.domain.enums.ScopeType;
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
import org.aibles.feature_flag.repository.PermissionGrantRepository;
import org.aibles.feature_flag.repository.ProjectRepository;
import org.aibles.feature_flag.repository.UserRepository;
import org.aibles.feature_flag.service.OrganizationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrganizationServiceImpl implements OrganizationService {

    private final OrganizationRepository organizationRepository;
    private final OrganizationMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final PermissionGrantRepository grantRepository;
    private final PermissionService permissionService;

    @Override
    @Transactional
    public OrganizationResponse create(CreateOrganizationRequest request) {
        if (organizationRepository.existsBySlug(request.getSlug())) {
            throw new DuplicateResourceException("Slug already taken: " + request.getSlug());
        }
        Organization org = Organization.builder()
                .name(request.getName())
                .slug(request.getSlug())
                .build();
        org = organizationRepository.save(org);

        UUID userId = permissionService.currentUserId();
        User user = userRepository.getReferenceById(userId);
        OrganizationMember member = OrganizationMember.builder()
                .organization(org)
                .user(user)
                .role(MemberRole.OWNER)
                .build();
        memberRepository.save(member);

        return toResponse(org);
    }

    @Override
    public List<OrganizationResponse> listMine() {
        UUID userId = permissionService.currentUserId();
        List<UUID> orgIds = memberRepository.findOrganizationIdsByUserId(userId);
        return organizationRepository.findAllById(orgIds).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    public OrganizationResponse get(UUID id) {
        Organization org = findById(id);
        if (!permissionService.isMember(id)) {
            throw new UnauthorizedException("You are not a member of this organisation");
        }
        return toResponse(org);
    }

    @Override
    @Transactional
    public OrganizationResponse update(UUID id, UpdateOrganizationRequest request) {
        permissionService.check(Action.ORG_UPDATE, PermissionService.ResourceRef.org(id));
        Organization org = findById(id);
        if (request.getName() != null) org.setName(request.getName());
        return toResponse(organizationRepository.save(org));
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        permissionService.check(Action.ORG_DELETE, PermissionService.ResourceRef.org(id));
        organizationRepository.deleteById(id);
    }

    @Override
    public List<MemberResponse> listMembers(UUID orgId) {
        if (!permissionService.isMember(orgId)) {
            throw new UnauthorizedException("You are not a member of this organisation");
        }
        return memberRepository.findAllByOrganizationId(orgId).stream()
                .map(m -> MemberResponse.builder()
                        .userId(m.getUser().getId())
                        .email(m.getUser().getEmail())
                        .firstName(m.getUser().getFirstName())
                        .lastName(m.getUser().getLastName())
                        .role(m.getRole())
                        .build())
                .toList();
    }

    @Override
    @Transactional
    public MemberResponse inviteMember(UUID orgId, InviteMemberRequest request) {
        permissionService.check(Action.MEMBER_INVITE, PermissionService.ResourceRef.org(orgId));
        if (!permissionService.effectiveActionsForOrg(permissionService.currentUserId(), orgId)
                .containsAll(PermissionService.actionsForRole(request.getRole()))) {
            throw new UnauthorizedException("You cannot invite a member with a role higher than your own");
        }
        if (memberRepository.existsByOrganizationIdAndUserId(orgId, request.getUserId())) {
            throw new DuplicateResourceException("User is already a member of this organisation");
        }
        User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User", request.getUserId()));
        Organization org = findById(orgId);

        OrganizationMember member = OrganizationMember.builder()
                .organization(org)
                .user(user)
                .role(request.getRole())
                .build();
        memberRepository.save(member);

        return MemberResponse.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .role(request.getRole())
                .build();
    }

    @Override
    @Transactional
    public void removeMember(UUID orgId, UUID userId) {
        permissionService.check(Action.MEMBER_MANAGE, PermissionService.ResourceRef.org(orgId));
        OrganizationMember member = memberRepository.findByOrganizationIdAndUserId(orgId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Member not found in organisation"));
        if (member.getRole() == MemberRole.OWNER &&
                memberRepository.countByOrganizationIdAndRole(orgId, MemberRole.OWNER) <= 1) {
            throw new UnauthorizedException("Cannot remove the only OWNER of an organisation");
        }
        memberRepository.delete(member);

        // Grants outlive membership, so revoke this user's project grants in the org too.
        List<UUID> projectIds = projectRepository.findAllByOrganizationId(orgId).stream()
                .map(Project::getId)
                .toList();
        if (!projectIds.isEmpty()) {
            grantRepository.deleteByUser_IdAndScopeTypeAndScopeIdIn(userId, ScopeType.PROJECT, projectIds);
        }
    }

    private Organization findById(UUID id) {
        return organizationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Organisation", id));
    }

    private OrganizationResponse toResponse(Organization org) {
        return OrganizationResponse.builder()
                .id(org.getId())
                .name(org.getName())
                .slug(org.getSlug())
                .createdAt(org.getCreatedAt())
                .build();
    }
}
