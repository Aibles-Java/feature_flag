package org.aibles.feature_flag.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.aibles.feature_flag.domain.entity.WebhookSubscription;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookSubscriptionRepository extends JpaRepository<WebhookSubscription, UUID> {

  /** Paginated fetch for the admin list endpoint (ADR-0003). */
  Page<WebhookSubscription> findAllByEnvironmentId(UUID environmentId, Pageable pageable);

  /**
   * Delivery path: the enabled subscriptions for one environment. Unbounded on purpose — bounded in
   * practice by how many endpoints an environment has, and the dispatcher needs all of them.
   */
  List<WebhookSubscription> findAllByEnvironmentIdAndEnabledTrue(UUID environmentId);

  Optional<WebhookSubscription> findByIdAndEnvironmentId(UUID id, UUID environmentId);
}
