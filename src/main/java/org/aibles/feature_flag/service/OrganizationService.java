package org.aibles.feature_flag.service;

import java.util.UUID;
import org.aibles.feature_flag.dto.request.CreateOrganizationRequest;
import org.aibles.feature_flag.dto.request.InviteMemberRequest;
import org.aibles.feature_flag.dto.request.UpdateOrganizationRequest;
import org.aibles.feature_flag.dto.response.MemberResponse;
import org.aibles.feature_flag.dto.response.OrganizationResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface OrganizationService {
  OrganizationResponse create(CreateOrganizationRequest request);

  Page<OrganizationResponse> listMine(Pageable pageable);

  OrganizationResponse get(UUID id);

  OrganizationResponse update(UUID id, UpdateOrganizationRequest request);

  void delete(UUID id);

  Page<MemberResponse> listMembers(UUID orgId, Pageable pageable);

  MemberResponse inviteMember(UUID orgId, InviteMemberRequest request);

  void removeMember(UUID orgId, UUID userId);
}
