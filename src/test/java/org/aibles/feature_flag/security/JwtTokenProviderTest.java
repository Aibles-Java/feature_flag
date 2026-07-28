package org.aibles.feature_flag.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.aibles.feature_flag.config.JwtProperties;
import org.aibles.feature_flag.domain.entity.User;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JwtTokenProvider}. Constructed directly (no Spring context) with an in-line
 * secret so token signing/validation is exercised end to end.
 */
class JwtTokenProviderTest {

  private static final String SECRET =
      "test-secret-key-that-is-long-enough-for-hmac-sha-256-algorithm-ok";
  private static final long ONE_HOUR = 3_600_000L;

  private final JwtTokenProvider provider =
      new JwtTokenProvider(new JwtProperties(SECRET, ONE_HOUR, ONE_HOUR));

  private UserPrincipal principal(UUID id, String email) {
    return UserPrincipal.from(
        User.builder().id(id).email(email).passwordHash("irrelevant").build());
  }

  @Test
  void generatesTokenThatValidatesAndRoundTripsClaims() {
    UUID userId = UUID.randomUUID();
    String token = provider.generateToken(principal(userId, "alice@example.com"));

    assertThat(provider.validateToken(token)).isTrue();
    assertThat(provider.getUserIdFromToken(token)).isEqualTo(userId);
    assertThat(provider.getEmailFromToken(token)).isEqualTo("alice@example.com");
  }

  @Test
  void rejectsExpiredToken() {
    // Negative expiry => the token is already expired the instant it is minted.
    JwtTokenProvider expiring = new JwtTokenProvider(new JwtProperties(SECRET, -1_000L, 1_000L));
    String token = expiring.generateToken(principal(UUID.randomUUID(), "bob@example.com"));

    assertThat(provider.validateToken(token)).isFalse();
  }

  @Test
  void rejectsMalformedToken() {
    assertThat(provider.validateToken("this.is.not-a-jwt")).isFalse();
    assertThat(provider.validateToken("garbage")).isFalse();
    assertThat(provider.validateToken("")).isFalse();
    assertThat(provider.validateToken(null)).isFalse();
  }

  @Test
  void rejectsTamperedToken() {
    // Two tokens from the same provider with distinct subjects => distinct payloads.
    String tokenA = provider.generateToken(principal(UUID.randomUUID(), "carol@example.com"));
    String tokenB = provider.generateToken(principal(UUID.randomUUID(), "erin@example.com"));
    String[] a = tokenA.split("\\.");
    String[] b = tokenB.split("\\.");

    // Splice B's payload under A's header+signature: the HMAC no longer covers the payload,
    // so validation must fail deterministically (no reliance on random signature bits).
    String tampered = a[0] + "." + b[1] + "." + a[2];

    assertThat(provider.validateToken(tampered)).isFalse();
  }

  @Test
  void rejectsTokenSignedWithDifferentSecret() {
    JwtTokenProvider other =
        new JwtTokenProvider(
            new JwtProperties(
                "a-completely-different-secret-key-also-long-enough-for-hs256!!",
                ONE_HOUR,
                ONE_HOUR));
    String foreignToken = other.generateToken(principal(UUID.randomUUID(), "dave@example.com"));

    assertThat(provider.validateToken(foreignToken)).isFalse();
  }
}
