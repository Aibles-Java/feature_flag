package org.aibles.feature_flag.repository;

import java.util.UUID;
import org.aibles.feature_flag.domain.entity.WebhookDeliveryAttempt;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookDeliveryAttemptRepository
    extends JpaRepository<WebhookDeliveryAttempt, UUID> {

  /** Paginated delivery history for one subscription (ADR-0003). */
  Page<WebhookDeliveryAttempt> findAllBySubscriptionId(UUID subscriptionId, Pageable pageable);
}
