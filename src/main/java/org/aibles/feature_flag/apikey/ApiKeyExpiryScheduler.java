package org.aibles.feature_flag.apikey;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aibles.feature_flag.config.ApiKeyProperties;
import org.aibles.feature_flag.domain.entity.EnvironmentApiKey;
import org.aibles.feature_flag.repository.EnvironmentApiKeyRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily scan for API keys approaching expiry. Runs on every instance; the claim inside {@link
 * ApiKeyExpiryNotifier} keeps each warning single. Registered as a scheduled task only when
 * warnings are enabled (see {@code ApiKeyExpiryConfig}); tests call {@link #scan()} directly.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ApiKeyExpiryScheduler {

  private final EnvironmentApiKeyRepository apiKeyRepository;
  private final ApiKeyExpiryNotifier notifier;
  private final ApiKeyProperties properties;
  private final Clock clock;

  @Scheduled(cron = "${app.api-key.expiry-warning.cron:0 0 9 * * *}")
  public void scan() {
    LocalDateTime now = LocalDateTime.now(clock);
    List<Integer> thresholds = properties.expiryWarning().thresholdsDays();
    LocalDateTime horizon = now.plusDays(Collections.max(thresholds));
    int notified = 0;
    int skipped = 0;
    for (EnvironmentApiKey key : apiKeyRepository.findExpiryCandidates(now, horizon)) {
      Integer due =
          dueThreshold(now, key.getExpiresAt(), key.getExpiryNoticeSentDays(), thresholds);
      if (due == null) {
        skipped++;
        continue;
      }
      try {
        if (notifier.claimAndWarn(key, due, now)) {
          notified++;
        } else {
          skipped++;
        }
      } catch (RuntimeException e) {
        // The failed key's own transaction rolled back; keep warning the others.
        log.warn(
            "API key expiry warning failed for key {}: {}",
            key.getId(),
            e.getClass().getSimpleName());
        skipped++;
      }
    }
    log.info("API key expiry scan: {} notified, {} skipped", notified, skipped);
  }

  /**
   * The threshold a key is due for now, or {@code null} when there is nothing new to warn about.
   * The due threshold is the smallest one the remaining lifetime has already dropped to, so a key
   * missed for a few days is warned once, at the threshold that matters now, instead of once for
   * every threshold it skipped.
   */
  static Integer dueThreshold(
      LocalDateTime now,
      LocalDateTime expiresAt,
      Integer alreadySentDays,
      List<Integer> thresholdsDays) {
    Duration remaining = Duration.between(now, expiresAt);
    Integer due =
        thresholdsDays.stream()
            .filter(days -> remaining.compareTo(Duration.ofDays(days)) <= 0)
            .min(Integer::compare)
            .orElse(null);
    if (due == null || (alreadySentDays != null && alreadySentDays <= due)) {
      return null;
    }
    return due;
  }
}
