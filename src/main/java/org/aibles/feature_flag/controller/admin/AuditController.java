package org.aibles.feature_flag.controller.admin;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.aibles.feature_flag.dto.response.AuditLogResponse;
import org.aibles.feature_flag.dto.response.PageResponse;
import org.aibles.feature_flag.service.impl.AuditService;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only access to an organisation's audit trail (issue #31). Scoped by org membership (VIEWER+)
 * inside {@link AuditService}; paginated, newest-first. Uses the British "organisations" base path
 * to stay consistent with the rest of the admin API.
 */
@RestController
@RequestMapping("/api/v1/organisations")
@RequiredArgsConstructor
public class AuditController {

  private final AuditService auditService;

  @GetMapping("/{orgId}/audit-log")
  public PageResponse<AuditLogResponse> listAuditLog(
      @PathVariable UUID orgId,
      @ParameterObject
          @PageableDefault(
              size = 20,
              sort = {"createdAt", "id"},
              direction = Sort.Direction.DESC)
          Pageable pageable) {
    return PageResponse.from(auditService.list(orgId, pageable));
  }
}
