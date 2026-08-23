package org.aibles.feature_flag.service;

import java.util.List;
import java.util.UUID;
import org.aibles.feature_flag.dto.request.CreateCustomRoleRequest;
import org.aibles.feature_flag.dto.response.CustomRoleResponse;

public interface CustomRoleService {

  List<CustomRoleResponse> list(UUID orgId);

  CustomRoleResponse create(UUID orgId, CreateCustomRoleRequest request);

  CustomRoleResponse update(UUID orgId, UUID roleId, CreateCustomRoleRequest request);

  void delete(UUID orgId, UUID roleId);
}
