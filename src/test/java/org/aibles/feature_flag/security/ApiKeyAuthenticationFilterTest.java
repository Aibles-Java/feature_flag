package org.aibles.feature_flag.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.repository.EnvironmentRepository;
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
 * {@code X-Environment-Key} short-circuits with a 401 problem detail, and that a valid key sets the
 * {@link Environment} principal and proceeds.
 */
@ExtendWith(MockitoExtension.class)
class ApiKeyAuthenticationFilterTest {

  private static final String HEADER = "X-Environment-Key";

  @Mock private EnvironmentRepository environmentRepository;
  @Mock private FilterChain filterChain;

  private ApiKeyAuthenticationFilter filter;

  @BeforeEach
  void setUp() {
    filter = new ApiKeyAuthenticationFilter(environmentRepository);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
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
    verifyNoInteractions(environmentRepository);
  }

  @Test
  void rejectsRequestWithUnknownApiKey() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(HEADER, "does-not-exist");
    MockHttpServletResponse response = new MockHttpServletResponse();

    // The filter hashes the header value before looking it up — never the raw key.
    when(environmentRepository.findByApiKeyHash(ApiKeyHasher.hash("does-not-exist")))
        .thenReturn(Optional.empty());

    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    assertThat(response.getContentAsString()).contains("Invalid API key");
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    verify(filterChain, never()).doFilter(request, response);
  }

  @Test
  void authenticatesAndProceedsForValidApiKey() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(HEADER, "valid-key");
    MockHttpServletResponse response = new MockHttpServletResponse();

    // Stored value is the hash; the plaintext "valid-key" arrives in the header and is hashed here.
    Environment env =
        Environment.builder()
            .id(UUID.randomUUID())
            .apiKeyHash(ApiKeyHasher.hash("valid-key"))
            .build();
    when(environmentRepository.findByApiKeyHash(ApiKeyHasher.hash("valid-key")))
        .thenReturn(Optional.of(env));

    filter.doFilter(request, response, filterChain);

    var auth = SecurityContextHolder.getContext().getAuthentication();
    assertThat(auth).isInstanceOf(ApiKeyAuthenticationToken.class);
    assertThat(auth.getPrincipal()).isEqualTo(env);
    assertThat(auth.isAuthenticated()).isTrue();
    // The filter must NOT short-circuit with a 401 — it hands off to the chain untouched.
    assertThat(response.getStatus()).isNotEqualTo(HttpStatus.UNAUTHORIZED.value());
    assertThat(response.getContentAsString()).isEmpty();
    verify(filterChain).doFilter(request, response);
  }

  @Test
  void stampsLastUsedAtWhenNeverUsedBefore() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(HEADER, "valid-key");
    MockHttpServletResponse response = new MockHttpServletResponse();

    UUID id = UUID.randomUUID();
    Environment env =
        Environment.builder()
            .id(id)
            .apiKeyHash(ApiKeyHasher.hash("valid-key"))
            .lastUsedAt(null) // never used → must be stamped
            .build();
    when(environmentRepository.findByApiKeyHash(ApiKeyHasher.hash("valid-key")))
        .thenReturn(Optional.of(env));

    filter.doFilter(request, response, filterChain);

    verify(environmentRepository)
        .touchLastUsedAt(eq(id), any(LocalDateTime.class), any(LocalDateTime.class));
    verify(filterChain).doFilter(request, response);
  }

  @Test
  void doesNotStampLastUsedAtWhenRecentlyUsed() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(HEADER, "valid-key");
    MockHttpServletResponse response = new MockHttpServletResponse();

    Environment env =
        Environment.builder()
            .id(UUID.randomUUID())
            .apiKeyHash(ApiKeyHasher.hash("valid-key"))
            .lastUsedAt(LocalDateTime.now()) // within the throttle window → skip the write
            .build();
    when(environmentRepository.findByApiKeyHash(ApiKeyHasher.hash("valid-key")))
        .thenReturn(Optional.of(env));

    filter.doFilter(request, response, filterChain);

    verify(environmentRepository, never()).touchLastUsedAt(any(), any(), any());
    verify(filterChain).doFilter(request, response);
  }
}
