package org.aibles.feature_flag.security.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import jakarta.servlet.FilterChain;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Unit tests for {@link SdkIpRateLimitFilter} — the pre-authentication per-IP throttle on the SDK
 * chain. {@link SdkRateLimitFilter} runs after {@code ApiKeyAuthenticationFilter} and buckets by
 * environment id, so it can never see a request that fails authentication; this filter is what caps
 * an anonymous caller probing keys.
 */
class SdkIpRateLimitFilterTest {

  private static final String SDK_PATH = "/api/v1/sdk/flags";

  private SdkIpRateLimitFilter filterWithCapacity(int capacity) {
    RateLimitProperties props = new RateLimitProperties();
    props.setEnabled(true);
    props.setSdkIp(new RateLimitProperties.Limit(capacity, Duration.ofMinutes(1)));
    return new SdkIpRateLimitFilter(new RateLimitService(props));
  }

  private MockHttpServletRequest requestFrom(String ip) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", SDK_PATH);
    request.setRemoteAddr(ip);
    return request;
  }

  @Test
  void refusesTrafficFromOneIpBeyondCapacity() throws Exception {
    SdkIpRateLimitFilter filter = filterWithCapacity(1);
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(requestFrom("198.51.100.7"), new MockHttpServletResponse(), chain);

    MockHttpServletResponse second = new MockHttpServletResponse();
    filter.doFilter(requestFrom("198.51.100.7"), second, chain);

    assertThat(second.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    assertThat(second.getHeader("Retry-After")).isNotNull();
    // Only the first request reached the rest of the chain.
    verify(chain, times(1)).doFilter(any(), any());
  }

  @Test
  void bucketsAreIndependentPerIp() throws Exception {
    SdkIpRateLimitFilter filter = filterWithCapacity(1);
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(requestFrom("198.51.100.7"), new MockHttpServletResponse(), chain);

    MockHttpServletResponse other = new MockHttpServletResponse();
    filter.doFilter(requestFrom("198.51.100.8"), other, chain);

    // A different source IP has its own fresh bucket — one abuser cannot lock everyone out.
    assertThat(other.getStatus()).isEqualTo(HttpStatus.OK.value());
    verify(chain, times(2)).doFilter(any(), any());
  }

  @Test
  void limitsRequestsThatCarryNoApiKeyAtAll() throws Exception {
    SdkIpRateLimitFilter filter = filterWithCapacity(1);
    FilterChain chain = mock(FilterChain.class);

    // No X-Environment-Key header: this is exactly the traffic SdkRateLimitFilter cannot key on,
    // because it would never run — ApiKeyAuthenticationFilter short-circuits with a 401 first.
    filter.doFilter(requestFrom("203.0.113.9"), new MockHttpServletResponse(), chain);

    MockHttpServletResponse second = new MockHttpServletResponse();
    filter.doFilter(requestFrom("203.0.113.9"), second, chain);

    assertThat(second.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
  }
}
