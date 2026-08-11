package org.aibles.feature_flag.dto.response;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;
import org.aibles.feature_flag.domain.enums.AuditAction;
import org.aibles.feature_flag.domain.enums.AuditEntityType;

/** One audit-trail entry returned by the audit-log read endpoint (issue #31). */
@Data
@Builder
public class AuditLogResponse {
  private UUID id;
  private UUID actorUserId;
  private UUID orgId;
  private AuditAction action;
  private AuditEntityType entityType;
  private UUID entityId;
  private Map<String, Object> beforeState;
  private Map<String, Object> afterState;
  private LocalDateTime createdAt;
}
