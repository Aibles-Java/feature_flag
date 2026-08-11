package org.aibles.feature_flag.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.aibles.feature_flag.domain.entity.FlagEnvironmentState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FlagEnvironmentStateRepository extends JpaRepository<FlagEnvironmentState, UUID> {
  Optional<FlagEnvironmentState> findByFeatureFlagIdAndEnvironmentId(
      UUID featureFlagId, UUID environmentId);

  @Query(
      "SELECT s FROM FlagEnvironmentState s JOIN FETCH s.featureFlag f WHERE s.environment.id = :envId AND f.archived = false")
  List<FlagEnvironmentState> findAllActiveByEnvironmentId(@Param("envId") UUID environmentId);

  List<FlagEnvironmentState> findAllByFeatureFlagId(UUID featureFlagId);

  /**
   * Every state row of an environment, archived flags included, ordered by flag key — the basis for
   * a clone and for an export snapshot (issue #38). Unlike {@link #findAllActiveByEnvironmentId} it
   * hides nothing, so a snapshot describes the environment completely; the deterministic order
   * makes two exports of the same state byte-identical and therefore diffable.
   */
  @Query(
      "SELECT s FROM FlagEnvironmentState s JOIN FETCH s.featureFlag f WHERE s.environment.id = :envId ORDER BY f.key")
  List<FlagEnvironmentState> findAllByEnvironmentIdOrderByFlagKey(
      @Param("envId") UUID environmentId);
}
