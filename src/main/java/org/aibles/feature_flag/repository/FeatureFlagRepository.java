package org.aibles.feature_flag.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.aibles.feature_flag.domain.entity.FeatureFlag;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeatureFlagRepository extends JpaRepository<FeatureFlag, UUID> {
  List<FeatureFlag> findAllByProjectIdAndArchivedFalse(UUID projectId);

  List<FeatureFlag> findAllByProjectIdAndArchivedTrue(UUID projectId);

  Optional<FeatureFlag> findByProjectIdAndKey(UUID projectId, String key);

  boolean existsByProjectIdAndKey(UUID projectId, String key);
}
