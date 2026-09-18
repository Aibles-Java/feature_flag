package org.aibles.feature_flag.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.domain.entity.EnvironmentApiKey;
import org.aibles.feature_flag.metrics.FeatureFlagMetrics;
import org.aibles.feature_flag.repository.EnvironmentApiKeyRepository;
import org.aibles.feature_flag.util.ApiKeyHasher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Unit tests for {@link ApiKeyAuthenticationFilter} (SDK chain). Verifies that a missing or unknown
 * {@code X-Environment-Key} short-circuits with a 401 problem detail, that a valid key sets the
 * {@link EnvironmentApiKey} principal and proceeds, and that revoked/expired keys are rejected with
 * distinct messages.
 */
@ExtendWith(MockitoExtension.class)
class ApiKeyAuthenticationFilterTest {

  private static final String HEADER = "X-Environment-Key";
  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 5, 12, 0);

  @Mock private EnvironmentApiKeyRepository apiKeyRepository;
  @Mock private FilterChain filterChain;

  private ApiKeyAuthenticationFilter filter;

  @BeforeEach
  void setUp() {
    Clock clock =
        Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
    filter =
        new ApiKeyAuthenticationFilter(
            apiKeyRepository, new FeatureFlagMetrics(new SimpleMeterRegistry()), clock);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  private EnvironmentApiKey key(LocalDateTime expiresAt, LocalDateTime revokedAt) {
    Environment env = Environment.builder().name("prod").build();
    env.setId(UUID.randomUUID());
    return EnvironmentApiKey.builder()
        .id(UUID.randomUUID())
        .environment(env)
        .name("ios")
        .keyHash(ApiKeyHasher.hash("plaintext"))
        .expiresAt(expiresAt)
        .revokedAt(revokedAt)
        .build();
  }

  @Test
  void rejectsRequestWithMissingApiKey() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    assertThat(response.getContentType()).contains(MediaType.APPLICATION_JSON_VALUE);
    assertThat(response.getContentAsString()).contains("Missing X-Environment-Key header");
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    verify(filterChain, never()).doFilter(request, response);
    verifyNoInteractions(apiKeyRepository);
  }

  @Test
  void rejectsRequestWithUnknownApiKey() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(HEADER, "does-not-exist");
    MockHttpServletResponse response = new MockHttpServletResponse();

    // The filter hashes the header value before looking it up — never the raw key.
    when(apiKeyRepository.findByKeyHash(ApiKeyHasher.hash("does-not-exist")))
        .thenReturn(Optional.empty());

    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    assertThat(response.getContentAsString()).contains("Invalid API key");
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    verify(filterChain, never()).doFilter(request, response);
  }

  @Test
  void authenticatesWithAValidKeyAndSetsTheKeyAsPrincipal() throws Exception {
    EnvironmentApiKey valid = key(null, null);
    when(apiKeyRepository.findByKeyHash(ApiKeyHasher.hash("plaintext")))
        .thenReturn(Optional.of(valid));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(HEADER, "plaintext");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    var auth = SecurityContextHolder.getContext().getAuthentication();
    assertThat(auth).isInstanceOf(ApiKeyAuthenticationToken.class);
    assertThat(auth.getPrincipal()).isSameAs(valid);
    assertThat(auth.isAuthenticated()).isTrue();
    // The filter must NOT short-circuit with a 401 — it hands off to the chain untouched.
    assertThat(response.getStatus()).isNotEqualTo(HttpStatus.UNAUTHORIZED.value());
    assertThat(response.getContentAsString()).isEmpty();
    verify(filterChain).doFilter(request, response);
  }

  @Test
  void rejectsARevokedKeyWithAMessageNamingRevocation() throws Exception {
    when(apiKeyRepository.findByKeyHash(ApiKeyHasher.hash("plaintext")))
        .thenReturn(Optional.of(key(null, NOW.minusDays(1))));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(HEADER, "plaintext");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    assertThat(response.getContentAsString()).contains("API key has been revoked");
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    verifyNoInteractions(filterChain);
  }

  @Test
  void rejectsAnExpiredKeyWithAMessageNamingExpiry() throws Exception {
    when(apiKeyRepository.findByKeyHash(ApiKeyHasher.hash("plaintext")))
        .thenReturn(Optional.of(key(NOW.minusMinutes(1), null)));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(HEADER, "plaintext");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    assertThat(response.getContentAsString()).contains("API key has expired");
    verifyNoInteractions(filterChain);
  }

  @Test
  void rejectsAKeyAtTheExactExpiryInstant() throws Exception {
    when(apiKeyRepository.findByKeyHash(ApiKeyHasher.hash("plaintext")))
        .thenReturn(Optional.of(key(NOW, null)));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(HEADER, "plaintext");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    verifyNoInteractions(filterChain);
  }

  @Test
  void unknownHashSaysOnlyInvalidAndNeverMentionsRevocationOrExpiry() throws Exception {
    when(apiKeyRepository.findByKeyHash(ApiKeyHasher.hash("plaintext")))
        .thenReturn(Optional.empty());
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(HEADER, "plaintext");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    // Nothing may leak about a key the caller does not hold.
    assertThat(response.getContentAsString()).contains("Invalid API key");
    assertThat(response.getContentAsString()).doesNotContain("revoked").doesNotContain("expired");
  }

  @Test
  void writesTheUsageStampWhenNeverUsedBefore() throws Exception {
    EnvironmentApiKey neverUsed = key(null, null);
    // lastUsedAt defaults to null — the "never authenticated before" branch of the throttle
    // guard, distinct from "stale" (a non-null timestamp older than the throttle window).
    when(apiKeyRepository.findByKeyHash(ApiKeyHasher.hash("plaintext")))
        .thenReturn(Optional.of(neverUsed));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(HEADER, "plaintext");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    verify(apiKeyRepository)
        .touchLastUsedAt(eq(neverUsed.getId()), eq(NOW), any(LocalDateTime.class));
    verify(filterChain).doFilter(request, response);
  }

  @Test
  void skipsTheUsageWriteWhenTheStampIsRecent() throws Exception {
    EnvironmentApiKey recent = key(null, null);
    recent.setLastUsedAt(NOW.minusMinutes(1));
    when(apiKeyRepository.findByKeyHash(ApiKeyHasher.hash("plaintext")))
        .thenReturn(Optional.of(recent));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(HEADER, "plaintext");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    verify(apiKeyRepository, never()).touchLastUsedAt(any(), any(), any());
    verify(filterChain).doFilter(request, response);
  }

  @Test
  void writesTheUsageStampWhenItIsStale() throws Exception {
    EnvironmentApiKey stale = key(null, null);
    stale.setLastUsedAt(NOW.minusHours(1));
    when(apiKeyRepository.findByKeyHash(ApiKeyHasher.hash("plaintext")))
        .thenReturn(Optional.of(stale));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(HEADER, "plaintext");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    verify(apiKeyRepository).touchLastUsedAt(eq(stale.getId()), eq(NOW), any());
    verify(filterChain).doFilter(request, response);
  }
}
