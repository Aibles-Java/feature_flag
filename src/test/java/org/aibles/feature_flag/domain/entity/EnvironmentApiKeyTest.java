package org.aibles.feature_flag.domain.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/**
 * The active/expired/revoked predicate is the single rule that decides authentication, the
 * per-environment cap and the legacy rotate endpoint's target, so it is pinned here directly rather
 * than only through the callers.
 */
class EnvironmentApiKeyTest {

  private static final ZoneId ZONE = ZoneId.systemDefault();
  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 5, 12, 0);

  private static Clock fixedAt(LocalDateTime moment) {
    return Clock.fixed(moment.atZone(ZONE).toInstant(), ZONE);
  }

  private static Clock now() {
    return fixedAt(NOW);
  }

  @Test
  void keyWithNoExpiryAndNoRevocationIsActive() {
    EnvironmentApiKey key = EnvironmentApiKey.builder().build();

    assertThat(key.isActive(now())).isTrue();
    assertThat(key.isExpired(now())).isFalse();
    assertThat(key.isRevoked()).isFalse();
  }

  @Test
  void keyExpiringInTheFutureIsStillActive() {
    EnvironmentApiKey key = EnvironmentApiKey.builder().expiresAt(NOW.plusSeconds(1)).build();

    assertThat(key.isActive(now())).isTrue();
  }

  @Test
  void keyIsExpiredAtTheExactExpiryInstant() {
    // The boundary is closed: expires_at is the first instant the key no longer works, so a
    // caller cannot squeeze a request through on the tick itself.
    EnvironmentApiKey key = EnvironmentApiKey.builder().expiresAt(NOW).build();

    assertThat(key.isExpired(now())).isTrue();
    assertThat(key.isActive(now())).isFalse();
  }

  @Test
  void keyPastItsExpiryIsNotActive() {
    EnvironmentApiKey key = EnvironmentApiKey.builder().expiresAt(NOW.minusSeconds(1)).build();

    assertThat(key.isExpired(now())).isTrue();
    assertThat(key.isActive(now())).isFalse();
  }

  @Test
  void revokedKeyIsNotActiveEvenWithNoExpiry() {
    EnvironmentApiKey key = EnvironmentApiKey.builder().revokedAt(NOW.minusDays(1)).build();

    assertThat(key.isRevoked()).isTrue();
    assertThat(key.isActive(now())).isFalse();
  }

  @Test
  void revocationWinsOverAFutureExpiry() {
    EnvironmentApiKey key =
        EnvironmentApiKey.builder().expiresAt(NOW.plusDays(30)).revokedAt(NOW).build();

    assertThat(key.isActive(now())).isFalse();
  }

  @Test
  void expiryIsEvaluatedAgainstTheSuppliedClockNotWallTime() {
    EnvironmentApiKey key = EnvironmentApiKey.builder().expiresAt(NOW).build();

    assertThat(key.isActive(fixedAt(NOW.minusHours(1)))).isTrue();
    assertThat(key.isActive(fixedAt(NOW.plusHours(1)))).isFalse();
  }

  @Test
  void fixedClockInstantIsRespected() {
    Clock clock = Clock.fixed(Instant.parse("2026-09-05T12:00:00Z"), ZoneId.of("UTC"));
    EnvironmentApiKey key =
        EnvironmentApiKey.builder().expiresAt(LocalDateTime.of(2026, 9, 5, 11, 59)).build();

    assertThat(key.isActive(clock)).isFalse();
  }
}
