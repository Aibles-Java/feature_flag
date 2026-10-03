package org.aibles.feature_flag.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.UUID;
import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.domain.entity.EnvironmentApiKey;
import org.aibles.feature_flag.domain.entity.Organization;
import org.aibles.feature_flag.domain.entity.Project;
import org.aibles.feature_flag.notification.event.ApiKeyExpiringEvent;
import org.aibles.feature_flag.repository.EnvironmentApiKeyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class ApiKeyExpiryNotifierTest {

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 3, 25, 9, 0);

  @Mock EnvironmentApiKeyRepository apiKeyRepository;
  @Mock ApplicationEventPublisher eventPublisher;

  private EnvironmentApiKey key() {
    Organization org = Organization.builder().id(UUID.randomUUID()).name("Acme").build();
    Project project =
        Project.builder().id(UUID.randomUUID()).organization(org).name("checkout").build();
    Environment env =
        Environment.builder().id(UUID.randomUUID()).project(project).name("production").build();
    return EnvironmentApiKey.builder()
        .id(UUID.randomUUID())
        .environment(env)
        .name("nightly-batch")
        .keyHash("secret-hash-value")
        .keyPrefix("a3f9c1d2")
        .expiresAt(NOW.plusDays(7))
        .lastUsedAt(NOW.minusHours(17))
        .build();
  }

  @Test
  void publishesNothingWhenTheClaimIsLost() {
    EnvironmentApiKey key = key();
    when(apiKeyRepository.claimExpiryNotice(key.getId(), 7, NOW)).thenReturn(0);

    boolean warned =
        new ApiKeyExpiryNotifier(apiKeyRepository, eventPublisher).claimAndWarn(key, 7, NOW);

    assertThat(warned).isFalse();
    verifyNoInteractions(eventPublisher);
  }

  @Test
  void publishesTheWarningWithoutKeyMaterialWhenTheClaimIsWon() {
    EnvironmentApiKey key = key();
    when(apiKeyRepository.claimExpiryNotice(key.getId(), 7, NOW)).thenReturn(1);

    boolean warned =
        new ApiKeyExpiryNotifier(apiKeyRepository, eventPublisher).claimAndWarn(key, 7, NOW);

    assertThat(warned).isTrue();
    ArgumentCaptor<ApiKeyExpiringEvent> captor = ArgumentCaptor.forClass(ApiKeyExpiringEvent.class);
    verify(eventPublisher).publishEvent(captor.capture());
    ApiKeyExpiringEvent event = captor.getValue();
    assertThat(event.environmentId()).isEqualTo(key.getEnvironment().getId());
    assertThat(event.environmentName()).isEqualTo("production");
    assertThat(event.projectName()).isEqualTo("checkout");
    assertThat(event.keyId()).isEqualTo(key.getId());
    assertThat(event.keyName()).isEqualTo("nightly-batch");
    assertThat(event.keyPrefix()).isEqualTo("a3f9c1d2");
    assertThat(event.expiresAt()).isEqualTo(NOW.plusDays(7));
    assertThat(event.lastUsedAt()).isEqualTo(NOW.minusHours(17));
    assertThat(event.daysLeft()).isEqualTo(7);
    assertThat(event.toString()).doesNotContain("secret-hash-value");
  }

  @ParameterizedTest(name = "{0}s left → {1} days")
  @CsvSource({"1, 1", "86400, 1", "86401, 2", "604799, 7", "604800, 7"})
  void daysLeftRoundsUpToWholeDays(long secondsLeft, long expectedDays) {
    assertThat(ApiKeyExpiryNotifier.daysLeft(NOW, NOW.plusSeconds(secondsLeft)))
        .isEqualTo(expectedDays);
  }
}
