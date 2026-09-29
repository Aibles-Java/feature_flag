package org.aibles.feature_flag.domain.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.*;
import org.aibles.feature_flag.domain.enums.Action;
import org.hibernate.annotations.CreationTimestamp;

/**
 * An organization-scoped, user-defined role: a named set of {@link Action}s a grant can reference.
 */
@Entity
@Table(
    name = "custom_role",
    uniqueConstraints = @UniqueConstraint(columnNames = {"organization_id", "name"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomRole {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "organization_id", nullable = false)
  private Organization organization;

  @Column(nullable = false, length = 100)
  private String name;

  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(name = "custom_role_action", joinColumns = @JoinColumn(name = "custom_role_id"))
  @Enumerated(EnumType.STRING)
  @Column(name = "action", nullable = false, length = 40)
  @Builder.Default
  private Set<Action> actions = new HashSet<>();

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;
}
