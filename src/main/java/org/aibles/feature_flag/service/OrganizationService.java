package org.aibles.feature_flag.service;

import java.util.List;
import java.util.UUID;
import org.aibles.feature_flag.dto.request.CreateOrganizationRequest;
import org.aibles.feature_flag.dto.request.InviteMemberRequest;
import org.aibles.feature_flag.dto.request.UpdateOrganizationRequest;
import org.aibles.feature_flag.dto.response.MemberResponse;
import org.aibles.feature_flag.dto.response.OrganizationResponse;

public interface OrganizationService {
  OrganizationResponse create(CreateOrganizationRequest request);

  List<OrganizationResponse> listMine();

  OrganizationResponse get(UUID id);

  OrganizationResponse update(UUID id, UpdateOrganizationRequest request);

  void delete(UUID id);

  List<MemberResponse> listMembers(UUID orgId);

  MemberResponse inviteMember(UUID orgId, InviteMemberRequest request);

  void removeMember(UUID orgId, UUID userId);
}
