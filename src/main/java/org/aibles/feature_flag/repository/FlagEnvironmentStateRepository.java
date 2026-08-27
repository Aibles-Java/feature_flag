package org.aibles.feature_flag.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.aibles.feature_flag.domain.entity.FlagEnvironmentState;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public interface FlagEnvironmentStateRepository extends JpaRepository<FlagEnvironmentState, UUID> {
  Optional<FlagEnvironmentState> findByFeatureFlagIdAndEnvironmentId(
      UUID featureFlagId, UUID environmentId);

  @Query(
      "SELECT s FROM FlagEnvironmentState s JOIN FETCH s.featureFlag f WHERE s.environment.id = :envId AND f.archived = false")
  List<FlagEnvironmentState> findAllActiveByEnvironmentId(@Param("envId") UUID environmentId);

  List<FlagEnvironmentState> findAllByFeatureFlagId(UUID featureFlagId);

  // --- issue #37: throttled last-evaluated tracking -----------------------------------------

  /**
   * Stamps every active flag state in one environment — the {@code GET /sdk/flags} case, which
   * evaluates all of them at once. One statement rather than one per flag, so the write cost does
   * not scale with the flag count.
   *
   * <p>The {@code threshold} guard makes this a no-op when the row was already stamped recently, so
   * it stays race-safe under concurrent SDK traffic and lets the caller throttle. Same shape as
   * {@code EnvironmentRepository.touchLastUsedAt}.
   *
   * <p>A bulk update deliberately bypasses Hibernate's lifecycle, so {@code @UpdateTimestamp} does
   * not fire and {@code updated_at} keeps meaning "configuration last changed".
   *
   * <p><strong>{@code REQUIRES_NEW} is required, not stylistic.</strong> The SDK evaluation path
   * runs in {@code @Transactional(readOnly = true)}; joining that transaction would make this an
   * UPDATE inside a read-only transaction, which PostgreSQL rejects outright while H2 lets it
   * through — so the default propagation would pass every test and fail in production. Its own
   * transaction also means a bookkeeping failure cannot roll back the evaluation. Cost is a second
   * pooled connection while the read is open, which the caller's throttle keeps rare.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  @Modifying
  @Query(
      "UPDATE FlagEnvironmentState s SET s.lastEvaluatedAt = :now "
          + "WHERE s.environment.id = :envId "
          + "AND (s.lastEvaluatedAt IS NULL OR s.lastEvaluatedAt < :threshold)")
  int touchLastEvaluatedAtForEnvironment(
      @Param("envId") UUID environmentId,
      @Param("now") LocalDateTime now,
      @Param("threshold") LocalDateTime threshold);

  /**
   * Single-flag variant, for {@code GET /sdk/flags/{flagKey}}. See the note above on propagation.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  @Modifying
  @Query(
      "UPDATE FlagEnvironmentState s SET s.lastEvaluatedAt = :now "
          + "WHERE s.featureFlag.id = :flagId AND s.environment.id = :envId "
          + "AND (s.lastEvaluatedAt IS NULL OR s.lastEvaluatedAt < :threshold)")
  int touchLastEvaluatedAt(
      @Param("flagId") UUID flagId,
      @Param("envId") UUID environmentId,
      @Param("now") LocalDateTime now,
      @Param("threshold") LocalDateTime threshold);

  // --- issue #37: hygiene report ------------------------------------------------------------

  /**
   * Every active (flag, environment) pair in a project, with both entities fetched so the caller
   * can map without extra queries.
   */
  @Query(
      value =
          "SELECT s FROM FlagEnvironmentState s "
              + "JOIN FETCH s.featureFlag f JOIN FETCH s.environment e "
              + "WHERE f.project.id = :projectId AND f.archived = false",
      countQuery =
          "SELECT COUNT(s) FROM FlagEnvironmentState s WHERE s.featureFlag.project.id = :projectId "
              + "AND s.featureFlag.archived = false")
  Page<FlagEnvironmentState> findHygieneRows(@Param("projectId") UUID projectId, Pageable pageable);

  /**
   * Stale pairs: not evaluated since {@code staleBefore}.
   *
   * <p>A never-evaluated flag only counts as stale once the flag itself is older than the cutoff —
   * otherwise every freshly created flag would be reported stale the moment it exists, which is
   * noise rather than signal.
   */
  @Query(
      value =
          "SELECT s FROM FlagEnvironmentState s "
              + "JOIN FETCH s.featureFlag f JOIN FETCH s.environment e "
              + "WHERE f.project.id = :projectId AND f.archived = false "
              + "AND ((s.lastEvaluatedAt IS NULL AND f.createdAt < :staleBefore) "
              + "     OR s.lastEvaluatedAt < :staleBefore)",
      countQuery =
          "SELECT COUNT(s) FROM FlagEnvironmentState s "
              + "WHERE s.featureFlag.project.id = :projectId AND s.featureFlag.archived = false "
              + "AND ((s.lastEvaluatedAt IS NULL AND s.featureFlag.createdAt < :staleBefore) "
              + "     OR s.lastEvaluatedAt < :staleBefore)")
  Page<FlagEnvironmentState> findStaleHygieneRows(
      @Param("projectId") UUID projectId,
      @Param("staleBefore") LocalDateTime staleBefore,
      Pageable pageable);

  /** Pairs whose flag has passed its expiry date. Expiry is a flag-level property. */
  @Query(
      value =
          "SELECT s FROM FlagEnvironmentState s "
              + "JOIN FETCH s.featureFlag f JOIN FETCH s.environment e "
              + "WHERE f.project.id = :projectId AND f.archived = false "
              + "AND f.expiresAt IS NOT NULL AND f.expiresAt < :now",
      countQuery =
          "SELECT COUNT(s) FROM FlagEnvironmentState s "
              + "WHERE s.featureFlag.project.id = :projectId AND s.featureFlag.archived = false "
              + "AND s.featureFlag.expiresAt IS NOT NULL AND s.featureFlag.expiresAt < :now")
  Page<FlagEnvironmentState> findExpiredHygieneRows(
      @Param("projectId") UUID projectId, @Param("now") LocalDateTime now, Pageable pageable);
}
