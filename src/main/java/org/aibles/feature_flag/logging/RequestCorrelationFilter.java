package org.aibles.feature_flag.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Establishes a request-correlation id for every inbound request and exposes it to logs (via {@link
 * MDC}), to downstream code (via a request attribute), and to callers (via the {@code X-Request-Id}
 * response header). See issue #28.
 *
 * <p><strong>Ordering:</strong> registered at {@code HIGHEST_PRECEDENCE} (see {@code
 * LoggingConfig}) so it runs before Spring Security's {@code FilterChainProxy} — and therefore
 * before all three security chains — guaranteeing every log line produced while handling the
 * request, including those from the auth filters, carries a {@code requestId}.
 *
 * <p><strong>MDC hygiene:</strong> the {@code finally} block clears the <em>entire</em> MDC, not
 * just the key it set. The authentication filters add {@link MdcKeys#USER_ID} / {@link
 * MdcKeys#ENV_ID} deeper in the chain; clearing everything here — at the outermost frame — is the
 * single choke point that prevents any correlation value from leaking onto this pooled thread's
 * next request.
 */
public class RequestCorrelationFilter extends OncePerRequestFilter {

  public static final String REQUEST_ID_HEADER = "X-Request-Id";

  /**
   * Accept a client-supplied id only if it is a short, boring token. This rejects newlines and
   * control characters (log-forging), unbounded values (log bloat), and anything that could confuse
   * JSON/log parsers — falling back to a fresh UUID when the incoming header is unusable.
   */
  private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String requestId = resolveRequestId(request);
    MDC.put(MdcKeys.REQUEST_ID, requestId);
    request.setAttribute(MdcKeys.REQUEST_ID, requestId);
    response.setHeader(REQUEST_ID_HEADER, requestId);
    try {
      filterChain.doFilter(request, response);
    } finally {
      MDC.clear();
    }
  }

  private String resolveRequestId(HttpServletRequest request) {
    String incoming = request.getHeader(REQUEST_ID_HEADER);
    if (StringUtils.hasText(incoming) && SAFE_REQUEST_ID.matcher(incoming).matches()) {
      return incoming;
    }
    return UUID.randomUUID().toString();
  }
}
