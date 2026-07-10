package org.aibles.feature_flag.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.aibles.feature_flag.domain.entity.Environment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface EnvironmentRepository extends JpaRepository<Environment, UUID> {
  List<Environment> findAllByProjectId(UUID projectId);

  Optional<Environment> findByApiKeyHash(String apiKeyHash);

  boolean existsByProjectIdAndName(UUID projectId, String name);

  /**
   * Stamps {@code last_used_at} for an authenticated environment. The {@code threshold} guard makes
   * this a no-op when the timestamp was already updated recently, so it stays race-safe under
   * concurrent SDK calls and lets the caller throttle writes on the hot path.
   */
  @Transactional
  @Modifying
  @Query(
      "UPDATE Environment e SET e.lastUsedAt = :now "
          + "WHERE e.id = :id AND (e.lastUsedAt IS NULL OR e.lastUsedAt < :threshold)")
  void touchLastUsedAt(
      @Param("id") UUID id,
      @Param("now") LocalDateTime now,
      @Param("threshold") LocalDateTime threshold);
}
