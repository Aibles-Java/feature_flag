package org.aibles.feature_flag.repository;

import java.util.UUID;
import org.aibles.feature_flag.domain.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

  /** Paginated audit history for one organisation (issue #31 read endpoint). */
  Page<AuditLog> findByOrgId(UUID orgId, Pageable pageable);
}
