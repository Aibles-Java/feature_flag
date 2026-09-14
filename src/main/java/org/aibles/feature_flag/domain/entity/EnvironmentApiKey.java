package org.aibles.feature_flag.domain.entity;

import jakarta.persistence.*;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

/**
 * One SDK credential for one environment. An environment may hold several, which is what lets a key
 * be rotated with a grace period instead of a hard cutover, and lets one leaked consumer's key be
 * withdrawn without cutting off the others.
 *
 * <p>The {@code expiresAt}/{@code revokedAt} pair mirrors {@link RefreshToken} so the two
 * credential lifecycles in this codebase read the same way.
 */
@Entity
@Table(name = "environment_api_key")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EnvironmentApiKey {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "environment_id", nullable = false)
  private Environment environment;

  /** Operator-facing label ("ios-app", "nightly-batch"). Deliberately not unique — see the spec. */
  @Column(nullable = false, length = 100)
  private String name;

  /** SHA-256 hash (lowercase hex) of the key. The plaintext is never stored. */
  @Column(name = "key_hash", nullable = false, unique = true, length = 64)
  private String keyHash;

  /**
   * First 8 characters of the plaintext. Not a secret: with the plaintext shown only once, this is
   * the only way an operator can tell which row corresponds to which deployed config. Empty for
   * rows backfilled by migration 019, whose plaintext was never recoverable.
   */
  @Column(name = "key_prefix", nullable = false, length = 8)
  @Builder.Default
  private String keyPrefix = "";

  /** {@code null} means the key never expires. */
  @Column(name = "expires_at")
  private LocalDateTime expiresAt;

  /** {@code null} means the key has not been revoked. Revocation is permanent. */
  @Column(name = "revoked_at")
  private LocalDateTime revokedAt;

  /** Last time this key successfully authenticated an SDK request. Coarse — throttled in filter. */
  @Column(name = "last_used_at")
  private LocalDateTime lastUsedAt;

  /**
   * Smallest expiry-warning threshold, in days, already sent for this key; {@code null} when none
   * has been. Written only by the conditional UPDATE in {@code
   * EnvironmentApiKeyRepository#claimExpiryNotice}, never through the entity.
   */
  @Column(name = "expiry_notice_sent_days")
  private Integer expiryNoticeSentDays;

  /** Nulled rather than cascaded: the key outlives the person who minted it. */
  @Column(name = "created_by")
  private UUID createdBy;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  public boolean isRevoked() {
    return revokedAt != null;
  }

  /** The boundary is closed: a key is expired at the exact instant named by {@code expiresAt}. */
  public boolean isExpired(Clock clock) {
    return expiresAt != null && !LocalDateTime.now(clock).isBefore(expiresAt);
  }

  public boolean isActive(Clock clock) {
    return !isRevoked() && !isExpired(clock);
  }
}
