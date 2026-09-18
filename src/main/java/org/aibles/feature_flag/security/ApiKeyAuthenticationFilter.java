package org.aibles.feature_flag.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.logging.MdcKeys;
import org.aibles.feature_flag.metrics.FeatureFlagMetrics;
import org.aibles.feature_flag.repository.EnvironmentRepository;
import org.aibles.feature_flag.util.ApiKeyHasher;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Slf4j
@RequiredArgsConstructor
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

  private static final String API_KEY_HEADER = "X-Environment-Key";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Only re-stamp {@code last_used_at} once per window, to avoid a DB write per SDK call. */
  private static final Duration LAST_USED_THROTTLE = Duration.ofMinutes(5);

  private final EnvironmentRepository environmentRepository;
  private final FeatureFlagMetrics metrics;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String apiKey = request.getHeader(API_KEY_HEADER);

    if (!StringUtils.hasText(apiKey)) {
      metrics.recordAuthFailure(FeatureFlagMetrics.AuthFailure.SDK_MISSING_KEY);
      writeUnauthorized(request, response, "Missing X-Environment-Key header");
      return;
    }

    Optional<Environment> environment =
        environmentRepository.findByApiKeyHash(ApiKeyHasher.hash(apiKey));
    if (environment.isEmpty()) {
      metrics.recordAuthFailure(FeatureFlagMetrics.AuthFailure.SDK_INVALID_KEY);
      writeUnauthorized(request, response, "Invalid API key");
      return;
    }

    Environment env = environment.get();
    touchLastUsedAt(env);

    ApiKeyAuthenticationToken authentication = new ApiKeyAuthenticationToken(env);
    SecurityContextHolder.getContext().setAuthentication(authentication);

    // Tag logs for this request with the resolved environment id. Cleared centrally by
    // RequestCorrelationFilter's finally block, so no per-request cleanup is needed here.
    MDC.put(MdcKeys.ENV_ID, env.getId().toString());

    filterChain.doFilter(request, response);
  }

  /**
   * Records SDK key usage, throttled to at most one write per {@link #LAST_USED_THROTTLE} window.
   * The in-memory check skips the DB round-trip for the common (recently-used) case; the
   * repository's threshold guard keeps the actual write race-safe.
   *
   * <p>A failed write is swallowed. This is audit bookkeeping on the evaluation hot path: letting a
   * lock timeout or an exhausted connection pool escape here would turn an authenticated, otherwise
   * successful {@code GET /api/v1/sdk/flags} into a 500 and cost the caller its flag values. Same
   * contract as {@code FlagEvaluationTracker.stamp} for {@code last_evaluated_at}. Nothing is
   * cached on failure, so the next request simply retries.
   */
  private void touchLastUsedAt(Environment env) {
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime threshold = now.minus(LAST_USED_THROTTLE);
    if (env.getLastUsedAt() != null && !env.getLastUsedAt().isBefore(threshold)) {
      return;
    }
    try {
      environmentRepository.touchLastUsedAt(env.getId(), now, threshold);
    } catch (RuntimeException e) {
      log.warn(
          "Could not stamp last_used_at for environment {}: {}",
          env.getId(),
          e.getClass().getSimpleName());
    }
  }

  /**
   * Answers with <b>401</b> and an RFC 7807 {@code application/problem+json} body in the same shape
   * {@code GlobalExceptionHandler} and {@link ProblemDetailAuthenticationEntryPoint} produce, so a
   * client parsing errors from the admin and SDK chains sees one schema rather than two.
   *
   * <p>The body is assembled as an explicit map for the reason spelled out on that entry point: a
   * plain {@code ObjectMapper} cannot serialise Spring's {@code ProblemDetail} to the right wire
   * shape — flattening its custom properties comes from a Jackson mixin registered on the MVC
   * mapper, not from the type itself, and filters run before MVC. Writing a {@code ProblemDetail}
   * through a bare mapper (as this did) emitted a nested {@code "properties"} key, a null {@code
   * instance}, and no correlation id.
   *
   * <p>No {@code WWW-Authenticate} header: RFC 7235 wants one on a 401, but there is no registered
   * scheme for a custom API-key header, and inventing one would only invite clients to implement an
   * auth flow that does not exist. The header name is documented in the OpenAPI spec instead.
   */
  private void writeUnauthorized(
      HttpServletRequest request, HttpServletResponse response, String detail) throws IOException {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", "about:blank");
    body.put("title", "Unauthorized");
    body.put("status", HttpStatus.UNAUTHORIZED.value());
    body.put("detail", detail);
    body.put("instance", request.getRequestURI());

    // Mirrors GlobalExceptionHandler.withRequestId so a reported 401 can be tied back to its exact
    // log lines. Set on the MDC by RequestCorrelationFilter, which runs before the security chains.
    String requestId = MDC.get(MdcKeys.REQUEST_ID);
    if (requestId != null) {
      body.put(MdcKeys.REQUEST_ID, requestId);
    }

    response.setStatus(HttpStatus.UNAUTHORIZED.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    MAPPER.writeValue(response.getOutputStream(), body);
  }
}
