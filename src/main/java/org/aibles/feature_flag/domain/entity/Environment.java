package org.aibles.feature_flag.domain.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(
    name = "environments",
    uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "name"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Environment {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "project_id", nullable = false)
  private Project project;

  @Column(nullable = false, length = 100)
  private String name;

  @Column(columnDefinition = "TEXT")
  private String description;

  /** SHA-256 hash (lowercase hex) of the SDK API key. The plaintext is never stored. */
  @Column(name = "api_key_hash", nullable = false, unique = true, length = 64)
  private String apiKeyHash;

  /** Last time this key successfully authenticated an SDK request (audit). Coarse — see filter. */
  @Column(name = "last_used_at")
  private LocalDateTime lastUsedAt;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;
}
