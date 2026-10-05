package org.aibles.feature_flag.security.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.aibles.feature_flag.security.UserPrincipal;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.util.UrlPathHelper;

/**
 * Per-user rate limit on {@code GET /api/v1/flags/environment-states} only (S-2.10, D-12, F22).
 * Runs on the admin chain right after {@code JwtAuthenticationFilter}, so the principal is already
 * resolved, and before the authorization filter and the controller: a throttled request does no
 * matrix/permission/DB work. Keyed by the JWT principal's user id (never email or IP); anonymous
 * requests are not bucketed (they get 401 downstream and must not be able to consume a user's
 * quota). GET and HEAD share one bucket. Matching is on the decoded path MVC routes on; variants
 * that Spring Security's strict firewall or MVC reject (trailing slash, {@code //}, {@code ;})
 * never reach the controller.
 */
public class MatrixRateLimitFilter extends AbstractRateLimitFilter {

  private static final UrlPathHelper PATH_HELPER = new UrlPathHelper();

  static final String MATRIX_PATH = "/api/v1/flags/environment-states";

  public MatrixRateLimitFilter(RateLimitService rateLimitService) {
    super(rateLimitService, RateLimitService.Scope.MATRIX);
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    // HEAD is served implicitly by @GetMapping and runs the same matrix work, so it shares the
    // bucket with GET (otherwise it would be a bypass of the limit).
    String method = request.getMethod();
    boolean counted = HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method);
    return !counted || !MATRIX_PATH.equals(pathWithinApplication(request));
  }

  /**
   * The path Spring MVC will route on: context path stripped, percent-decoded, {@code ;params} and
   * duplicate slashes removed. Matching the raw request URI would let {@code /%65nvironment-states}
   * reach the controller (decoded by MVC) without being counted.
   */
  private static String pathWithinApplication(HttpServletRequest request) {
    return PATH_HELPER.getPathWithinApplication(request);
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
