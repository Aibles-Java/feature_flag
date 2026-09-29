package org.aibles.feature_flag.domain.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.*;
import org.aibles.feature_flag.domain.enums.WebhookEventType;
import org.hibernate.annotations.CreationTimestamp;

/**
 * One HTTP delivery attempt, recorded per try so a failing endpoint's retry history is visible
 * (issue #36 — "failed deliveries are retried with exponential backoff and tracked").
 */
@Entity
@Table(name = "webhook_delivery_attempt")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebhookDeliveryAttempt {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /** Plain UUID column — written from the async dispatcher thread. FK cascade lives in the DB. */
  @Column(name = "subscription_id", nullable = false)
  private UUID subscriptionId;

  @Column(name = "event_type", nullable = false, length = 32)
  @Enumerated(EnumType.STRING)
  private WebhookEventType eventType;

  /** 1-based attempt number within a single delivery (1..max-attempts). */
  @Column(nullable = false)
  private int attempt;

  @Column(nullable = false)
  private boolean succeeded;

  /** HTTP status when a response was received; null when the request never completed. */
  @Column(name = "response_status")
  private Integer responseStatus;

  /**
   * Exception class name only — never {@code getMessage()}. A connection-level failure puts the
   * full target URL in the message, and a webhook URL can itself embed a token (the same reasoning
   * {@code SlackNotifier} documents).
   */
  @Column(length = 255)
  private String error;

  @Column(name = "duration_ms")
  private Long durationMs;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;
}
