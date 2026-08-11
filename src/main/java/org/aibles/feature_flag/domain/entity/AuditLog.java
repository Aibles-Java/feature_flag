package org.aibles.feature_flag.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.aibles.feature_flag.domain.enums.AuditAction;
import org.aibles.feature_flag.domain.enums.AuditEntityType;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Append-only audit trail of admin mutations (issue #31). Rows are only ever inserted — there is no
 * update or delete path anywhere in the application. {@link #beforeState}/{@link #afterState}
 * capture the entity's public (DTO) shape immediately before and after the change (null for
 * create/delete respectively), stored as JSON (jsonb on PostgreSQL).
 */
@Entity
@Table(name = "audit_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /** The user who performed the mutation (from the JWT principal). */
  @Column(name = "actor_user_id")
  private UUID actorUserId;

  /** Organisation the mutation belongs to — the scope the read endpoint filters on. */
  @Column(name = "org_id", nullable = false)
  private UUID orgId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private AuditAction action;

  @Enumerated(EnumType.STRING)
  @Column(name = "entity_type", nullable = false, length = 32)
  private AuditEntityType entityType;

  /** Id of the entity acted on (the new id for creates). */
  @Column(name = "entity_id")
  private UUID entityId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "before_state")
  private Map<String, Object> beforeState;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "after_state")
  private Map<String, Object> afterState;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;
}
