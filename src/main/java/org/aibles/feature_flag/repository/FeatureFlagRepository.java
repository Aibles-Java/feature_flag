package org.aibles.feature_flag.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.aibles.feature_flag.domain.entity.FeatureFlag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FeatureFlagRepository extends JpaRepository<FeatureFlag, UUID> {
  /**
   * Every flag in the project, archived included — used when a new environment backfills its
   * FlagEnvironmentState rows. Archived flags need a row too, or unarchiving one later would
   * resurrect the missing-state gap.
   */
  List<FeatureFlag> findAllByProjectId(UUID projectId);

  List<FeatureFlag> findAllByProjectIdAndArchivedFalse(UUID projectId);

  List<FeatureFlag> findAllByProjectIdAndArchivedTrue(UUID projectId);

  /** Paginated variants for the admin flag list endpoints (issue #33). */
  Page<FeatureFlag> findAllByProjectIdAndArchivedFalse(UUID projectId, Pageable pageable);

  Page<FeatureFlag> findAllByProjectIdAndArchivedTrue(UUID projectId, Pageable pageable);

  Optional<FeatureFlag> findByProjectIdAndKey(UUID projectId, String key);

  boolean existsByProjectIdAndKey(UUID projectId, String key);

  /**
   * One page of non-archived flags plus the project's total non-archived flag count in the SAME
   * statement (scalar subquery), so the matrix endpoint (S-2.5) needs no separate count query. Each
   * row is {@code [FeatureFlag, Long total]}. Constrained by {@code project_id}; the caller passes
   * a fixed-sort {@link Pageable}.
   */
  @Query(
      "SELECT f, (SELECT COUNT(f2) FROM FeatureFlag f2 WHERE f2.project.id = :projectId "
          + "AND f2.archived = false) FROM FeatureFlag f "
          + "WHERE f.project.id = :projectId AND f.archived = false ORDER BY f.createdAt, f.id")
  List<Object[]> findActivePageWithTotal(@Param("projectId") UUID projectId, Pageable pageable);

  long countByProjectIdAndArchivedFalse(UUID projectId);
}
