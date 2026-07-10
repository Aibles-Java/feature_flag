package org.aibles.feature_flag.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.metrics.FeatureFlagMetrics;
import org.aibles.feature_flag.repository.EnvironmentRepository;
import org.aibles.feature_flag.util.ApiKeyHasher;
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

  private final EnvironmentRepository environmentRepository;
  private final FeatureFlagMetrics metrics;

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

    Optional<Environment> environment =
        environmentRepository.findByApiKeyHash(ApiKeyHasher.hash(apiKey));
    if (environment.isEmpty()) {
      metrics.recordAuthFailure(FeatureFlagMetrics.AuthFailure.SDK_INVALID_KEY);
      writeUnauthorized(response, "Invalid API key");
      return;
    }

    Environment env = environment.get();
    touchLastUsedAt(env);

    ApiKeyAuthenticationToken authentication = new ApiKeyAuthenticationToken(env);
    SecurityContextHolder.getContext().setAuthentication(authentication);
    filterChain.doFilter(request, response);
  }

  /**
   * Records SDK key usage, throttled to at most one write per {@link #LAST_USED_THROTTLE} window.
   * The in-memory check skips the DB round-trip for the common (recently-used) case; the
   * repository's threshold guard keeps the actual write race-safe.
   */
  private void touchLastUsedAt(Environment env) {
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime threshold = now.minus(LAST_USED_THROTTLE);
    if (env.getLastUsedAt() == null || env.getLastUsedAt().isBefore(threshold)) {
      environmentRepository.touchLastUsedAt(env.getId(), now, threshold);
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
