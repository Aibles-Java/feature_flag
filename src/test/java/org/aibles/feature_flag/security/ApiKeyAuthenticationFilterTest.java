package org.aibles.feature_flag.security;

import jakarta.servlet.FilterChain;
import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.repository.EnvironmentRepository;
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

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ApiKeyAuthenticationFilter} (SDK chain). Verifies that a
 * missing or unknown {@code X-Environment-Key} short-circuits with a 401 problem
 * detail, and that a valid key sets the {@link Environment} principal and proceeds.
 */
@ExtendWith(MockitoExtension.class)
class ApiKeyAuthenticationFilterTest {

    private static final String HEADER = "X-Environment-Key";

    @Mock
    private EnvironmentRepository environmentRepository;
    @Mock
    private FilterChain filterChain;

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

        when(environmentRepository.findByApiKey("does-not-exist")).thenReturn(Optional.empty());

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

        Environment env = Environment.builder().id(UUID.randomUUID()).apiKey("valid-key").build();
        when(environmentRepository.findByApiKey("valid-key")).thenReturn(Optional.of(env));

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
}
