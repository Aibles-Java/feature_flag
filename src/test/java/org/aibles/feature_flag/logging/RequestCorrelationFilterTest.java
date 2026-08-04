package org.aibles.feature_flag.logging;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Unit tests for {@link RequestCorrelationFilter}. Covers request-id resolution (honor vs
 * generate), propagation to the response header/MDC/request attribute, and — the
 * acceptance-critical property — that the MDC is fully cleared after each request so nothing leaks
 * across requests that reuse the same pooled thread.
 */
class RequestCorrelationFilterTest {

  private final RequestCorrelationFilter filter = new RequestCorrelationFilter();

  @AfterEach
  void tearDown() {
    MDC.clear();
  }

  @Test
  void honorsIncomingRequestId() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(RequestCorrelationFilter.REQUEST_ID_HEADER, "abc-123");
    MockHttpServletResponse response = new MockHttpServletResponse();

    AtomicReference<String> seenInMdc = new AtomicReference<>();
    FilterChain chain = (req, res) -> seenInMdc.set(MDC.get(MdcKeys.REQUEST_ID));

    filter.doFilter(request, response, chain);

    assertThat(seenInMdc.get()).isEqualTo("abc-123");
    assertThat(response.getHeader(RequestCorrelationFilter.REQUEST_ID_HEADER)).isEqualTo("abc-123");
    assertThat(request.getAttribute(MdcKeys.REQUEST_ID)).isEqualTo("abc-123");
  }

  @Test
  void generatesRequestIdWhenHeaderAbsent() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    AtomicReference<String> seenInMdc = new AtomicReference<>();
    FilterChain chain = (req, res) -> seenInMdc.set(MDC.get(MdcKeys.REQUEST_ID));

    filter.doFilter(request, response, chain);

    String generated = seenInMdc.get();
    assertThat(generated).isNotBlank();
    // Response header echoes the same generated id.
    assertThat(response.getHeader(RequestCorrelationFilter.REQUEST_ID_HEADER)).isEqualTo(generated);
  }

  @Test
  void rejectsUnsafeIncomingRequestIdAndGeneratesInstead() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    // Newline = classic log-forging attempt; must not be honored.
    request.addHeader(RequestCorrelationFilter.REQUEST_ID_HEADER, "evil\nInjected: true");
    MockHttpServletResponse response = new MockHttpServletResponse();

    AtomicReference<String> seenInMdc = new AtomicReference<>();
    FilterChain chain = (req, res) -> seenInMdc.set(MDC.get(MdcKeys.REQUEST_ID));

    filter.doFilter(request, response, chain);

    assertThat(seenInMdc.get()).doesNotContain("\n").isNotEqualTo("evil\nInjected: true");
  }

  @Test
  void clearsMdcAfterRequestEvenWhenDownstreamThrows() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    FilterChain chain =
        (req, res) -> {
          // Simulate a downstream (auth) filter adding more correlation context.
          MDC.put(MdcKeys.USER_ID, "user-1");
          throw new RuntimeException("boom");
        };

    try {
      filter.doFilter(request, response, chain);
    } catch (RuntimeException ignored) {
      // expected — the filter must still have cleaned up in its finally block.
    }

    assertThat(MDC.get(MdcKeys.REQUEST_ID)).isNull();
    assertThat(MDC.get(MdcKeys.USER_ID)).isNull();
  }

  @Test
  void doesNotLeakRequestIdOntoNextRequestOnSameThread() throws Exception {
    // First request carries an explicit id.
    MockHttpServletRequest first = new MockHttpServletRequest();
    first.addHeader(RequestCorrelationFilter.REQUEST_ID_HEADER, "first-req");
    filter.doFilter(first, new MockHttpServletResponse(), (req, res) -> {});

    // MDC must be empty between requests on the same (pooled) thread.
    assertThat(MDC.get(MdcKeys.REQUEST_ID)).isNull();

    // Second request has no header → gets its own generated id, never "first-req".
    MockHttpServletRequest second = new MockHttpServletRequest();
    AtomicReference<String> secondId = new AtomicReference<>();
    filter.doFilter(
        second,
        new MockHttpServletResponse(),
        (req, res) -> secondId.set(MDC.get(MdcKeys.REQUEST_ID)));

    assertThat(secondId.get()).isNotBlank().isNotEqualTo("first-req");
  }
}
