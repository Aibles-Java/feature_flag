package org.aibles.feature_flag.controller.admin;

import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.aibles.feature_flag.dto.request.CreateOrganizationRequest;
import org.aibles.feature_flag.dto.request.InviteMemberRequest;
import org.aibles.feature_flag.dto.request.UpdateOrganizationRequest;
import org.aibles.feature_flag.dto.response.MemberResponse;
import org.aibles.feature_flag.dto.response.OrganizationResponse;
import org.aibles.feature_flag.dto.response.PageResponse;
import org.aibles.feature_flag.service.OrganizationService;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/organisations")
@RequiredArgsConstructor
public class OrganizationController {

  private final OrganizationService organizationService;

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public OrganizationResponse create(@Valid @RequestBody CreateOrganizationRequest request) {
    return organizationService.create(request);
  }

  @GetMapping
  public PageResponse<OrganizationResponse> listMine(
      @ParameterObject
          @PageableDefault(
              size = 20,
              sort = {"createdAt", "id"},
              direction = Sort.Direction.ASC)
          Pageable pageable) {
    return PageResponse.from(organizationService.listMine(pageable));
  }

  @GetMapping("/{orgId}")
  public OrganizationResponse get(@PathVariable UUID orgId) {
    return organizationService.get(orgId);
  }

  @PutMapping("/{orgId}")
  public OrganizationResponse update(
      @PathVariable UUID orgId, @Valid @RequestBody UpdateOrganizationRequest request) {
    return organizationService.update(orgId, request);
  }

  @DeleteMapping("/{orgId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID orgId) {
    organizationService.delete(orgId);
  }

  @GetMapping("/{orgId}/members")
  public PageResponse<MemberResponse> listMembers(
      @PathVariable UUID orgId,
      @ParameterObject
          @PageableDefault(
              size = 20,
              sort = {"createdAt", "id"},
              direction = Sort.Direction.ASC)
          Pageable pageable) {
    return PageResponse.from(organizationService.listMembers(orgId, pageable));
  }

  @PostMapping("/{orgId}/members")
  @ResponseStatus(HttpStatus.CREATED)
  public MemberResponse inviteMember(
      @PathVariable UUID orgId, @Valid @RequestBody InviteMemberRequest request) {
    return organizationService.inviteMember(orgId, request);
  }

  @DeleteMapping("/{orgId}/members/{userId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void removeMember(@PathVariable UUID orgId, @PathVariable UUID userId) {
    organizationService.removeMember(orgId, userId);
  }
}
