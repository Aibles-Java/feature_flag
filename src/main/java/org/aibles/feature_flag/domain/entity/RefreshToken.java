package org.aibles.feature_flag.domain.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "refresh_token")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefreshToken {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /**
   * Plain UUID column rather than a {@code @ManyToOne} association — deliberately avoids
   * lazy-loading pitfalls on the refresh hot path. The FK is enforced at the DB level.
   */
  @Column(name = "user_id", nullable = false)
  private UUID userId;

  /** Groups every rotation step of one login/device, so a reuse can revoke the whole family. */
  @Column(name = "family_id", nullable = false)
  private UUID familyId;

  /** SHA-256 hash (lowercase hex) of the opaque token. The plaintext is never stored. */
  @Column(name = "token_hash", nullable = false, unique = true, length = 64)
  private String tokenHash;

  @Column(name = "expires_at", nullable = false)
  private LocalDateTime expiresAt;

  @Column(name = "rotated_at")
  private LocalDateTime rotatedAt;

  @Column(name = "revoked_at")
  private LocalDateTime revokedAt;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;
}
