package org.aibles.feature_flag.repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.aibles.feature_flag.domain.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

  Optional<RefreshToken> findByTokenHash(String tokenHash);

  /**
   * Marks a token as rotated, but only if it has not been rotated already. Returns the number of
   * affected rows: 1 for the caller that won the race, 0 for every other. Reuse detection depends
   * on this being a single conditional UPDATE — a read-then-write would let two concurrent
   * refreshes both succeed.
   */
  @Modifying
  @Query("UPDATE RefreshToken r SET r.rotatedAt = :now WHERE r.id = :id AND r.rotatedAt IS NULL")
  int consume(@Param("id") UUID id, @Param("now") LocalDateTime now);

  /** Revokes every not-yet-revoked token in a family — the response to a detected reuse. */
  @Modifying
  @Query(
      "UPDATE RefreshToken r SET r.revokedAt = :now "
          + "WHERE r.familyId = :familyId AND r.revokedAt IS NULL")
  int revokeFamily(@Param("familyId") UUID familyId, @Param("now") LocalDateTime now);

  int deleteByExpiresAtBefore(LocalDateTime cutoff);
}
