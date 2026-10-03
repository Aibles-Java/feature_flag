package org.aibles.feature_flag.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.aibles.feature_flag.domain.entity.FeatureFlag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
