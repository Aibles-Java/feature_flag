package org.aibles.feature_flag.security.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import jakarta.servlet.FilterChain;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.aibles.feature_flag.domain.entity.User;
import org.aibles.feature_flag.security.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class MatrixRateLimitFilterTest {

  private static final String PATH = "/api/v1/flags/environment-states";

  private MatrixRateLimitFilter filter(int capacity, boolean enabled) {
    RateLimitProperties props = new RateLimitProperties();
    props.setEnabled(enabled);
    props.setMatrix(new RateLimitProperties.Limit(capacity, Duration.ofMinutes(1)));
    return new MatrixRateLimitFilter(new RateLimitService(props));
  }

  private void login(UUID id) {
    UserPrincipal p =
        UserPrincipal.from(User.builder().id(id).email("u@example.test").passwordHash("x").build());
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(p, null, List.of()));
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void secondRequestOverLimitIs429WithRetryAfterAndChainNotInvoked() throws Exception {
    var f = filter(1, true);
    FilterChain chain = mock(FilterChain.class);
    login(UUID.randomUUID());
    f.doFilter(new MockHttpServletRequest("GET", PATH), new MockHttpServletResponse(), chain);
    MockHttpServletResponse second = new MockHttpServletResponse();
    f.doFilter(new MockHttpServletRequest("GET", PATH), second, chain);
    assertThat(second.getStatus()).isEqualTo(429);
    assertThat(second.getHeader("Retry-After")).isNotNull();
    assertThat(second.getContentAsString()).contains("Too Many Requests");
    verify(chain, times(1)).doFilter(any(), any());
  }

  @Test
  void otherPathsAreNotFiltered() throws Exception {
    var f = filter(1, true);
    FilterChain chain = mock(FilterChain.class);
    login(UUID.randomUUID());
    for (int i = 0; i < 5; i++) {
      f.doFilter(
          new MockHttpServletRequest("GET", "/api/v1/flags/" + UUID.randomUUID()),
          new MockHttpServletResponse(),
          chain);
    }
    verify(chain, times(5)).doFilter(any(), any());
  }

  @Test
  void unauthenticatedRequestsAreNotBucketed() throws Exception {
    var f = filter(1, true);
    FilterChain chain = mock(FilterChain.class);
    for (int i = 0; i < 3; i++) {
      f.doFilter(new MockHttpServletRequest("GET", PATH), new MockHttpServletResponse(), chain);
    }
    verify(chain, times(3)).doFilter(any(), any());
  }

  @Test
  void disabledMasterSwitchBypasses() throws Exception {
    var f = filter(1, false);
    FilterChain chain = mock(FilterChain.class);
    login(UUID.randomUUID());
    for (int i = 0; i < 3; i++) {
      f.doFilter(new MockHttpServletRequest("GET", PATH), new MockHttpServletResponse(), chain);
    }
    verify(chain, times(3)).doFilter(any(), any());
  }
}
