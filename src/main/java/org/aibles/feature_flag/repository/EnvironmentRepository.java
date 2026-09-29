package org.aibles.feature_flag.repository;

import java.util.List;
import java.util.UUID;
import org.aibles.feature_flag.domain.entity.Environment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EnvironmentRepository extends JpaRepository<Environment, UUID> {
  /**
   * Unbounded fetch — used when auto-creating a FlagEnvironmentState for every env of a project.
   */
  List<Environment> findAllByProjectId(UUID projectId);

  /** Paginated fetch for the admin environments list endpoint (issue #33). */
  Page<Environment> findAllByProjectId(UUID projectId, Pageable pageable);

  boolean existsByProjectIdAndName(UUID projectId, String name);
}
