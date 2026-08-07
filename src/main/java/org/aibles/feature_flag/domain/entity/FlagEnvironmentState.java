package org.aibles.feature_flag.domain.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(
    name = "flag_environment_states",
    uniqueConstraints = @UniqueConstraint(columnNames = {"feature_flag_id", "environment_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FlagEnvironmentState {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "feature_flag_id", nullable = false)
  private FeatureFlag featureFlag;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "environment_id", nullable = false)
  private Environment environment;

  @Column(nullable = false)
  @Builder.Default
  private boolean enabled = false;

  @Column(columnDefinition = "TEXT")
  private String value;

  @Column(name = "rollout_percent", nullable = false)
  @Builder.Default
  private int rolloutPercent = 100;

  /**
   * Last time an SDK evaluation returned this state (issue #37). Null means never evaluated.
   *
   * <p>Written only by {@code FlagEnvironmentStateRepository.touchLastEvaluatedAt*}, i.e. a bulk
   * JPQL update — never via this setter on a managed entity. That is deliberate: a bulk update
   * bypasses Hibernate's lifecycle, so it does <strong>not</strong> fire {@code @UpdateTimestamp}
   * on {@link #updatedAt}. Setting it through the entity instead would bump {@code updatedAt} on
   * every read and destroy its meaning ("when was this flag's configuration last changed").
   */
  @Column(name = "last_evaluated_at")
  private LocalDateTime lastEvaluatedAt;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;
}
