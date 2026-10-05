package org.aibles.feature_flag.security.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.aibles.feature_flag.security.UserPrincipal;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Per-user rate limit on {@code GET /api/v1/flags/environment-states} only (S-2.10, D-12, F22).
 * Runs on the admin chain right after {@code JwtAuthenticationFilter}, so the principal is already
 * resolved, and before the authorization filter and the controller: a throttled request does no
 * matrix/permission/DB work. Keyed by the JWT principal's user id (never email or IP); anonymous
 * requests are not bucketed (they get 401 downstream and must not be able to consume a user's
 * quota).
 */
public class MatrixRateLimitFilter extends AbstractRateLimitFilter {

  static final String MATRIX_PATH = "/api/v1/flags/environment-states";

  public MatrixRateLimitFilter(RateLimitService rateLimitService) {
    super(rateLimitService, RateLimitService.Scope.MATRIX);
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !HttpMethod.GET.matches(request.getMethod())
        || !MATRIX_PATH.equals(request.getRequestURI());
  }

  @Override
  protected String resolveKey(HttpServletRequest request) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.getPrincipal() instanceof UserPrincipal principal) {
      return principal.getId().toString();
    }
    return null;
  }
}
