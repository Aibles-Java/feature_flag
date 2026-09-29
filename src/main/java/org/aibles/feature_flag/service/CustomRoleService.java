package org.aibles.feature_flag.service;

import java.util.UUID;
import org.aibles.feature_flag.dto.request.CreateCustomRoleRequest;
import org.aibles.feature_flag.dto.response.CustomRoleResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface CustomRoleService {

  Page<CustomRoleResponse> list(UUID orgId, Pageable pageable);

  CustomRoleResponse create(UUID orgId, CreateCustomRoleRequest request);

  CustomRoleResponse update(UUID orgId, UUID roleId, CreateCustomRoleRequest request);

  void delete(UUID orgId, UUID roleId);
}
