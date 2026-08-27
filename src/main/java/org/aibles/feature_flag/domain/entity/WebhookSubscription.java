package org.aibles.feature_flag.domain.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import lombok.*;
import org.aibles.feature_flag.domain.enums.WebhookEventType;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/** A subscriber's endpoint for one environment's flag change events (issue #36). */
@Entity
@Table(name = "webhook_subscription")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebhookSubscription {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /**
   * Plain UUID column rather than a {@code @ManyToOne} — the dispatcher runs on an async thread
   * with no open session, so a lazy association would blow up on access. The FK (cascade delete) is
   * enforced at the DB level by migration 012.
   */
  @Column(name = "environment_id", nullable = false)
  private UUID environmentId;

  @Column(nullable = false, length = 2048)
  private String url;

  /**
   * The shared secret, AES-256-GCM encrypted — see {@code SecretCipher}. <strong>Not a
   * hash:</strong> HMAC signing needs the plaintext on every delivery, so this must stay
   * reversible.
   */
  @Column(name = "secret_ciphertext", nullable = false, columnDefinition = "TEXT")
  private String secretCiphertext;

  @Column(nullable = false)
  private boolean enabled;

  /**
   * Eager on purpose: the dispatcher reads the event types off-session on an async thread, so a
   * lazy collection would throw. The set is at most {@link WebhookEventType} in size, so there is
   * no fan-out cost.
   */
  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(
      name = "webhook_subscription_event_type",
      joinColumns = @JoinColumn(name = "subscription_id"))
  @Column(name = "event_type", nullable = false, length = 32)
  @Enumerated(EnumType.STRING)
  @Builder.Default
  private Set<WebhookEventType> eventTypes = EnumSet.noneOf(WebhookEventType.class);

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  public boolean listensFor(WebhookEventType eventType) {
    return enabled && eventTypes.contains(eventType);
  }
}
