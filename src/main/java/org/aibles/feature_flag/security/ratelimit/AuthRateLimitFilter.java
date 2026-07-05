package org.aibles.feature_flag.security.ratelimit;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Per-IP rate limit on {@code /api/v1/auth/**} (brute-force protection, issue #26). These
 * endpoints are unauthenticated ({@code permitAll}), so the only stable client identifier is the
 * source IP.
 *
 * <p>The key is {@link HttpServletRequest#getRemoteAddr()} — the direct TCP peer, which a client
 * cannot spoof. If the app is deployed behind a reverse proxy/load balancer, set
 * {@code server.forward-headers-strategy=framework} so {@code getRemoteAddr()} reflects the real
 * client instead of the proxy (we deliberately do NOT trust a raw {@code X-Forwarded-For} header,
 * which an attacker could forge to rotate the key and bypass the limit).
 */
public class AuthRateLimitFilter extends AbstractRateLimitFilter {

    private static final String AUTH_PATH_PREFIX = "/api/v1/auth/";

    public AuthRateLimitFilter(RateLimitService rateLimitService) {
        super(rateLimitService, RateLimitService.Scope.AUTH);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // This filter lives on the catch-all admin chain; only limit the auth endpoints.
        return !request.getRequestURI().startsWith(AUTH_PATH_PREFIX);
    }

    @Override
    protected String resolveKey(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
