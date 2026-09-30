package org.aibles.feature_flag.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.aibles.feature_flag.config.ApiKeyProperties;
import org.aibles.feature_flag.domain.entity.EnvironmentApiKey;
import org.aibles.feature_flag.repository.EnvironmentApiKeyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ApiKeyExpirySchedulerTest {

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 3, 1, 9, 0);
  private static final List<Integer> THRESHOLDS = List.of(30, 7, 1);

  private EnvironmentApiKeyRepository repository;
  private ApiKeyExpiryNotifier notifier;
  private ApiKeyExpiryScheduler scheduler;

  @BeforeEach
  void setUp() {
    repository = mock(EnvironmentApiKeyRepository.class);
    notifier = mock(ApiKeyExpiryNotifier.class);
    Clock clock =
        Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
    scheduler =
        new ApiKeyExpiryScheduler(repository, notifier, new ApiKeyProperties(null, null), clock);
  }

  @ParameterizedTest(name = "{0}h left, already sent {1} → due {2}")
  @CsvSource(
      nullValues = "null",
      value = {
        "600, null, 30", // 25 days, nothing sent yet
        "720, null, 30", // exactly 30 days
        "576, 30, null", // 24 days, 30 already sent
        "169, 30, null", // 7 days + 1 hour: still only inside 30
        "168, 30, 7", // exactly 7 days
        "120, 30, 7", // 5 days after missed scans: warned once, at 7
        "120, 7, null",
        "12, 7, 1",
        "12, 1, null",
        "744, null, null" // 31 days: beyond every threshold
      })
  void dueThreshold(long hoursLeft, Integer alreadySent, Integer expectedDue) {
    assertThat(
            ApiKeyExpiryScheduler.dueThreshold(
                NOW, NOW.plusHours(hoursLeft), alreadySent, THRESHOLDS))
        .isEqualTo(expectedDue);
  }

  @Test
  void scansUpToTheLargestThresholdAndClaimsTheDueOne() {
    EnvironmentApiKey key =
        EnvironmentApiKey.builder().id(UUID.randomUUID()).expiresAt(NOW.plusDays(5)).build();
    when(repository.findExpiryCandidates(NOW, NOW.plusDays(30))).thenReturn(List.of(key));
    when(notifier.claimAndWarn(key, 7, NOW)).thenReturn(true);

    scheduler.scan();

    verify(notifier).claimAndWarn(key, 7, NOW);
  }

  @Test
  void aKeyWithNothingNewDueIsNotClaimed() {
    EnvironmentApiKey key =
        EnvironmentApiKey.builder()
            .id(UUID.randomUUID())
            .expiresAt(NOW.plusDays(5))
            .expiryNoticeSentDays(7)
            .build();
    when(repository.findExpiryCandidates(NOW, NOW.plusDays(30))).thenReturn(List.of(key));

    scheduler.scan();

    verify(notifier, never()).claimAndWarn(any(), anyInt(), any());
  }

  @Test
  void oneFailingKeyDoesNotStopTheScan() {
    EnvironmentApiKey failing =
        EnvironmentApiKey.builder().id(UUID.randomUUID()).expiresAt(NOW.plusDays(5)).build();
    EnvironmentApiKey next =
        EnvironmentApiKey.builder().id(UUID.randomUUID()).expiresAt(NOW.plusDays(6)).build();
    when(repository.findExpiryCandidates(NOW, NOW.plusDays(30))).thenReturn(List.of(failing, next));
    when(notifier.claimAndWarn(failing, 7, NOW)).thenThrow(new IllegalStateException("boom"));
    when(notifier.claimAndWarn(next, 7, NOW)).thenReturn(true);

    scheduler.scan();

    verify(notifier).claimAndWarn(next, 7, NOW);
  }
}
