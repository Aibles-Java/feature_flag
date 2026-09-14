package org.aibles.feature_flag.apikey;

import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.domain.entity.EnvironmentApiKey;
import org.aibles.feature_flag.notification.event.ApiKeyExpiringEvent;
import org.aibles.feature_flag.repository.EnvironmentApiKeyRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sends one expiry warning for one key, at most once per threshold.
 *
 * <p>Two traps, both pinned by {@code ApiKeyExpiryWarningIntegrationTest}:
 *
 * <ol>
 *   <li>Every notification listener is {@code @TransactionalEventListener(AFTER_COMMIT)} without
 *       {@code fallbackExecution}, so an event published with no active transaction is dropped: no
 *       listener runs, nothing is logged. The event is published inside this method's transaction,
 *       which also means it goes out only once the claim has committed.
 *   <li>This is a separate bean from {@link ApiKeyExpiryScheduler} on purpose. A
 *       {@code @Transactional} method called on {@code this} skips the Spring proxy and runs with
 *       no transaction, which lands straight in trap 1.
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class ApiKeyExpiryNotifier {

  private final EnvironmentApiKeyRepository apiKeyRepository;
  private final ApplicationEventPublisher eventPublisher;

  /**
   * Claims {@code thresholdDays} for {@code key} and publishes the warning only if this call won
   * the claim. {@code REQUIRES_NEW} so one key's failure rolls back only its own claim.
   *
   * @return {@code true} when the warning was published
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean claimAndWarn(EnvironmentApiKey key, int thresholdDays, LocalDateTime now) {
    if (apiKeyRepository.claimExpiryNotice(key.getId(), thresholdDays, now) != 1) {
      return false;
    }
    Environment env = key.getEnvironment();
    eventPublisher.publishEvent(
        new ApiKeyExpiringEvent(
            env.getId(),
            env.getName(),
            env.getProject().getName(),
            key.getId(),
            key.getName(),
            key.getKeyPrefix(),
            key.getExpiresAt(),
            key.getLastUsedAt(),
            daysLeft(now, key.getExpiresAt())));
    return true;
  }

  /** Remaining lifetime rounded up to whole days, so 6 days 23 hours reads as 7. */
  static long daysLeft(LocalDateTime now, LocalDateTime expiresAt) {
    long seconds = Duration.between(now, expiresAt).getSeconds();
    return (seconds + 86_399) / 86_400;
  }
}
