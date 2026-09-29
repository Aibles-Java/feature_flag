package org.aibles.feature_flag.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.aibles.feature_flag.domain.entity.AuditLog;
import org.aibles.feature_flag.domain.enums.Action;
import org.aibles.feature_flag.domain.enums.AuditAction;
import org.aibles.feature_flag.domain.enums.AuditEntityType;
import org.aibles.feature_flag.dto.response.AuditLogResponse;
import org.aibles.feature_flag.repository.AuditLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * Writes and reads the append-only audit trail (issue #31).
 *
 * <p>{@link #record} is called from within a mutating service method's own transaction, so the
 * audit row commits atomically with the change (a rollback leaves no orphan audit row, and a
 * committed mutation always has exactly one). It never updates or deletes rows. {@code
 * before}/{@code after} are the entity's response-DTO shape; callers must pass a NON-secret view
 * (e.g. never the plaintext API key) — this class simply serializes whatever it is given.
 */
@Service
@RequiredArgsConstructor
public class AuditService {

  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  /**
   * A self-contained mapper (Boot 4.1 doesn't autoconfigure an {@code ObjectMapper} in non-web
   * contexts, and audit serialization must not depend on the web stack). {@code findAndRegister
   * modules()} picks up JSR-310 so entity {@code LocalDateTime} fields serialize as ISO strings.
   */
  private final ObjectMapper objectMapper =
      new ObjectMapper()
          .findAndRegisterModules()
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  private final AuditLogRepository auditLogRepository;
  private final PermissionService permissionService;

  /**
   * Appends one audit row for a mutation. {@code before}/{@code after} may be {@code null} (create
   * has no before, delete has no after) or any object whose JSON shape should be captured.
   */
  public void record(
      AuditEntityType entityType,
      UUID entityId,
      AuditAction action,
      UUID orgId,
      Object before,
      Object after) {
    auditLogRepository.save(
        AuditLog.builder()
            .actorUserId(permissionService.currentUserId())
            .orgId(orgId)
            .action(action)
            .entityType(entityType)
            .entityId(entityId)
            .beforeState(toMap(before))
            .afterState(toMap(after))
            .build());
  }

  /** Paginated audit history for an organisation. Requires {@code AUDIT_READ} on that org. */
  public Page<AuditLogResponse> list(UUID orgId, Pageable pageable) {
    permissionService.check(Action.AUDIT_READ, PermissionService.ResourceRef.org(orgId));
    return auditLogRepository.findByOrgId(orgId, pageable).map(this::toResponse);
  }

  private Map<String, Object> toMap(Object value) {
    return value == null ? null : objectMapper.convertValue(value, MAP_TYPE);
  }

  private AuditLogResponse toResponse(AuditLog log) {
    return AuditLogResponse.builder()
        .id(log.getId())
        .actorUserId(log.getActorUserId())
        .orgId(log.getOrgId())
        .action(log.getAction())
        .entityType(log.getEntityType())
        .entityId(log.getEntityId())
        .beforeState(log.getBeforeState())
        .afterState(log.getAfterState())
        .createdAt(log.getCreatedAt())
        .build();
  }
}
