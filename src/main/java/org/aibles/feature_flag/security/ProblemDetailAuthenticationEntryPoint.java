package org.aibles.feature_flag.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.aibles.feature_flag.logging.MdcKeys;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * Answers unauthenticated requests on the admin chain with <b>401</b> and an RFC 7807 {@code
 * application/problem+json} body matching the shape {@code GlobalExceptionHandler} produces for
 * errors raised inside MVC.
 *
 * <p><b>Why this exists.</b> Spring Security only defaults to a 401-capable entry point when a
 * built-in authentication mechanism (form login, HTTP Basic) is configured. The admin chain
 * authenticates with a custom JWT filter and configures neither, so the default was {@code
 * Http403ForbiddenEntryPoint} and every missing-, malformed- or expired-token request came back as
 * <b>403</b>. That is wrong on its own terms — 403 means "authenticated, but not allowed" — and it
 * actively breaks clients: an SPA cannot distinguish "your session expired, refresh the token" from
 * "you may not do this", so a token-refresh interceptor keyed on 401 (the conventional choice)
 * never fires and users silently see empty pages until they reload.
 *
 * <p>Note this deliberately does <em>not</em> also register an {@code AccessDeniedHandler}. On this
 * chain the only rule is {@code anyRequest().authenticated()} and no method security is in use, so
 * an authenticated-but-forbidden request cannot reach the filter-level 403 path — role denials come
 * from {@code PermissionService} throwing {@code UnauthorizedException} and are rendered by {@code
 * GlobalExceptionHandler}. An unreachable handler could not be tested and would only look like
 * coverage it does not provide.
 */
public class ProblemDetailAuthenticationEntryPoint implements AuthenticationEntryPoint {

  /**
   * Built locally rather than injected. A plain {@code ObjectMapper} cannot serialise Spring's
   * {@code ProblemDetail} to the right wire shape anyway — flattening its custom properties comes
   * from a Jackson mixin registered on the MVC mapper, not from the type itself — so the body is
   * assembled as an explicit map. That also pins field order and keeps this independent of whether
   * an {@code ObjectMapper} bean exists in a given test slice.
   */
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Override
  public void commence(
      HttpServletRequest request,
      HttpServletResponse response,
      AuthenticationException authException)
      throws IOException {

    // RFC 7235: a 401 must state how to authenticate. No `realm` parameter — it is meaningless for
    // bearer tokens and would make a browser render a native credential prompt for an XHR.
    //
    // Only set when absent, and this guard is load-bearing rather than defensive. The admin chain
    // has no securityMatcher, so it is the catch-all — which means it also handles the container's
    // forward to /error. An unauthenticated /actuator/** request is answered by the management
    // chain with 401 + `WWW-Authenticate: Basic`, and the ensuing error dispatch then re-enters
    // *this* chain because /error does not match /actuator/**. Overwriting unconditionally would
    // replace that Basic challenge with Bearer, and a Prometheus scraper following the challenge
    // would attempt the wrong scheme. Verified against a running instance.
    if (!response.containsHeader(HttpHeaders.WWW_AUTHENTICATE)) {
      response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
    }

    // Deliberately coarse: which of missing header / bad signature / expired / unknown user applies
    // is a signal for the defender and goes to the correlated server log, not to a caller who could
    // use it to probe token state. Same contract as the refresh-token rejection path.
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", "about:blank");
    body.put("title", "Unauthorized");
    body.put("status", HttpStatus.UNAUTHORIZED.value());
    body.put("detail", "Authentication required");
    body.put("instance", request.getRequestURI());

    // Mirrors GlobalExceptionHandler.withRequestId so an error a user reports can be tied back to
    // the exact log lines, including for failures that never reach a controller. Set on the MDC by
    // RequestCorrelationFilter, which runs at highest precedence — before the security chains.
    String requestId = MDC.get(MdcKeys.REQUEST_ID);
    if (requestId != null) {
      body.put(MdcKeys.REQUEST_ID, requestId);
    }

    response.setStatus(HttpStatus.UNAUTHORIZED.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    objectMapper.writeValue(response.getOutputStream(), body);
  }
}
