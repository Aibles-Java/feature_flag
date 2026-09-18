package org.aibles.feature_flag.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.aibles.feature_flag.domain.entity.EnvironmentApiKey;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public interface EnvironmentApiKeyRepository extends JpaRepository<EnvironmentApiKey, UUID> {

  /**
   * Authentication lookup. Fetches the environment in the same query: the SDK filter runs outside a
   * transaction, so a lazy proxy would fail on first access in the controller.
   *
   * <p>Returns the row regardless of validity — the filter distinguishes "revoked" from "expired"
   * from "unknown" for the response message, which it cannot do if the predicate is in the WHERE
   * clause.
   */
  @Query("SELECT k FROM EnvironmentApiKey k JOIN FETCH k.environment WHERE k.keyHash = :hash")
  Optional<EnvironmentApiKey> findByKeyHash(@Param("hash") String hash);

  /**
   * The same lookup narrowed to active keys — used where the reason for rejection is irrelevant.
   */
  @Query(
      "SELECT k FROM EnvironmentApiKey k JOIN FETCH k.environment "
          + "WHERE k.keyHash = :hash AND k.revokedAt IS NULL "
          + "AND (k.expiresAt IS NULL OR k.expiresAt > :now)")
  Optional<EnvironmentApiKey> findActiveByKeyHash(
      @Param("hash") String hash, @Param("now") LocalDateTime now);

  Page<EnvironmentApiKey> findAllByEnvironmentId(UUID environmentId, Pageable pageable);

  /** Backs the per-environment cap and the legacy rotate endpoint's unambiguous-target check. */
  @Query(
      "SELECT COUNT(k) FROM EnvironmentApiKey k WHERE k.environment.id = :environmentId "
          + "AND k.revokedAt IS NULL AND (k.expiresAt IS NULL OR k.expiresAt > :now)")
  long countActiveByEnvironmentId(
      @Param("environmentId") UUID environmentId, @Param("now") LocalDateTime now);

  /**
   * Active keys of one environment, oldest first — the legacy rotate endpoint resolves its target
   * here.
   */
  @Query(
      "SELECT k FROM EnvironmentApiKey k JOIN FETCH k.environment "
          + "WHERE k.environment.id = :environmentId AND k.revokedAt IS NULL "
          + "AND (k.expiresAt IS NULL OR k.expiresAt > :now) ORDER BY k.createdAt ASC")
  List<EnvironmentApiKey> findActiveByEnvironmentId(
      @Param("environmentId") UUID environmentId, @Param("now") LocalDateTime now);

  /**
   * The environment-level "last used" that {@code environments.last_used_at} used to hold, now
   * derived from its keys. Issue #56's deletion guard needs exactly this quantity.
   */
  @Query(
      "SELECT MAX(k.lastUsedAt) FROM EnvironmentApiKey k WHERE k.environment.id = :environmentId")
  Optional<LocalDateTime> findLastUsedAtByEnvironmentId(@Param("environmentId") UUID environmentId);

  /**
   * Stamps {@code last_used_at}. The {@code threshold} guard makes this a no-op when the timestamp
   * was updated recently, so it stays race-safe under concurrent SDK calls and lets the caller
   * throttle writes on the hot path. Must stay a bulk UPDATE — setting the field on a managed
   * entity would bump nothing here but would load the row on every SDK read.
   *
   * <p>This method's only caller, {@code ApiKeyAuthenticationFilter}, runs as a servlet filter
   * before any service-layer transaction exists, so {@code REQUIRES_NEW} costs nothing today — with
   * no ambient transaction it behaves exactly like {@code REQUIRED}. It is declared anyway so this
   * method stays safe if it is ever called from inside a read-only service transaction, which is
   * precisely the trap {@link FlagEnvironmentStateRepository#touchLastEvaluatedAtForEnvironment}
   * documents: there, the caller chain genuinely does run inside {@code @Transactional(readOnly =
   * true)}, and joining it would make this UPDATE fail against PostgreSQL (while passing silently
   * against H2). That specific bug does not exist at this call site today, but the annotation is
   * cheap insurance against it recurring here.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  @Modifying
  @Query(
      "UPDATE EnvironmentApiKey k SET k.lastUsedAt = :now "
          + "WHERE k.id = :id AND (k.lastUsedAt IS NULL OR k.lastUsedAt < :threshold)")
  void touchLastUsedAt(
      @Param("id") UUID id,
      @Param("now") LocalDateTime now,
      @Param("threshold") LocalDateTime threshold);
}
