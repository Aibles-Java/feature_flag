package org.aibles.feature_flag.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.aibles.feature_flag.domain.enums.MemberRole;
import org.aibles.feature_flag.domain.enums.ScopeType;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Grants a user a role on a specific scope (a project today), elevating their effective
 * permissions there. Exactly one of {@link #role} or {@link #customRole} is set. {@code scopeId}
 * is a polymorphic reference validated in the service layer, not by a DB FK.
 */
@Entity
@Table(name = "permission_grant",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "scope_type", "scope_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PermissionGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 20)
    private ScopeType scopeType;

    @Column(name = "scope_id", nullable = false)
    private UUID scopeId;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private MemberRole role;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "custom_role_id")
    private CustomRole customRole;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
