package org.aibles.feature_flag.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.aibles.feature_flag.domain.entity.EnvironmentApiKey;
import org.aibles.feature_flag.logging.MdcKeys;
import org.aibles.feature_flag.metrics.FeatureFlagMetrics;
import org.aibles.feature_flag.repository.EnvironmentApiKeyRepository;
import org.aibles.feature_flag.util.ApiKeyHasher;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@RequiredArgsConstructor
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

  private static final String API_KEY_HEADER = "X-Environment-Key";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Only re-stamp {@code last_used_at} once per window, to avoid a DB write per SDK call. */
  private static final Duration LAST_USED_THROTTLE = Duration.ofMinutes(5);

  private final EnvironmentApiKeyRepository apiKeyRepository;
  private final FeatureFlagMetrics metrics;
  private final Clock clock;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String apiKey = request.getHeader(API_KEY_HEADER);

    if (!StringUtils.hasText(apiKey)) {
      metrics.recordAuthFailure(FeatureFlagMetrics.AuthFailure.SDK_MISSING_KEY);
      writeUnauthorized(response, "Missing X-Environment-Key header");
      return;
    }

    Optional<EnvironmentApiKey> found = apiKeyRepository.findByKeyHash(ApiKeyHasher.hash(apiKey));
    if (found.isEmpty()) {
      rejectKey(response, "Invalid API key");
      return;
    }

    // Validity is evaluated here rather than in the query so the three failures stay
    // distinguishable. Telling a caller their key expired rather than "invalid" is the
    // difference between a five-minute fix and a support ticket, and it reveals nothing:
    // only someone already holding the key can see the distinction. An unknown hash gets
    // the flat "Invalid API key" above, so nothing leaks about keys the caller lacks.
    EnvironmentApiKey key = found.get();
    if (key.isRevoked()) {
      rejectKey(response, "API key has been revoked");
      return;
    }
    if (key.isExpired(clock)) {
      rejectKey(response, "API key has expired");
      return;
    }

    touchLastUsedAt(key);

    SecurityContextHolder.getContext().setAuthentication(new ApiKeyAuthenticationToken(key));

    // Tag logs for this request with the resolved environment id. Cleared centrally by
    // RequestCorrelationFilter's finally block, so no per-request cleanup is needed here.
    MDC.put(MdcKeys.ENV_ID, key.getEnvironment().getId().toString());

    filterChain.doFilter(request, response);
  }

  /** All key rejections share one counter — the metric must not become a revocation oracle. */
  private void rejectKey(HttpServletResponse response, String detail) throws IOException {
    metrics.recordAuthFailure(FeatureFlagMetrics.AuthFailure.SDK_INVALID_KEY);
    writeUnauthorized(response, detail);
  }

  /**
   * Records SDK key usage, throttled to at most one write per {@link #LAST_USED_THROTTLE} window.
   * The in-memory check skips the DB round-trip for the common (recently-used) case; the
   * repository's threshold guard keeps the actual write race-safe.
   */
  private void touchLastUsedAt(EnvironmentApiKey key) {
    LocalDateTime now = LocalDateTime.now(clock);
    LocalDateTime threshold = now.minus(LAST_USED_THROTTLE);
    if (key.getLastUsedAt() == null || key.getLastUsedAt().isBefore(threshold)) {
      apiKeyRepository.touchLastUsedAt(key.getId(), now, threshold);
    }
  }

  private void writeUnauthorized(HttpServletResponse response, String detail) throws IOException {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
    problem.setTitle("Unauthorized");
    problem.setDetail(detail);
    problem.setType(URI.create("about:blank"));

    response.setStatus(HttpStatus.UNAUTHORIZED.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    MAPPER.writeValue(response.getWriter(), problem);
  }
}
