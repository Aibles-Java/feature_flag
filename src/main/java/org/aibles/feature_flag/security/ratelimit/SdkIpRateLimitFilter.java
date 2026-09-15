package org.aibles.feature_flag.security.ratelimit;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Per-IP rate limit on {@code /api/v1/sdk/**}, applied <strong>before</strong> {@code
 * ApiKeyAuthenticationFilter} authenticates the key.
 *
 * <p><b>Why this exists alongside {@link SdkRateLimitFilter}.</b> That filter buckets by
 * environment id and therefore runs <em>after</em> authentication — but {@code
 * ApiKeyAuthenticationFilter} short-circuits a missing or unknown key with a 401 and never calls
 * {@code doFilter}, so it could never observe a failed attempt; and even if it did, its {@code
 * resolveKey} has no principal to key on and returns {@code null} (no limit). The result was that
 * authenticated SDK traffic was capped while anonymous key-probing was not, each attempt still
 * costing a SHA-256 and an indexed {@code findByApiKeyHash} lookup. This filter closes that gap,
 * mirroring how {@code AuthRateLimitFilter} caps the unauthenticated {@code /api/v1/auth/**}
 * endpoints on the admin chain.
 *
 * <p>Both limits apply to an authenticated request: this one per source IP (default 600/min, set
 * above the per-key limit so a NAT'd fleet of clients is not the first thing to break), then {@link
 * SdkRateLimitFilter} per environment.
 *
 * <p>The key is {@link HttpServletRequest#getRemoteAddr()} — the direct TCP peer, which a client
 * cannot spoof. Behind a reverse proxy set {@code server.forward-headers-strategy=framework}; a raw
 * {@code X-Forwarded-For} is deliberately not trusted, since forging it would rotate the bucket key
 * and bypass the limit.
 */
public class SdkIpRateLimitFilter extends AbstractRateLimitFilter {

  public SdkIpRateLimitFilter(RateLimitService rateLimitService) {
    super(rateLimitService, RateLimitService.Scope.SDK_IP);
  }

  @Override
  protected String resolveKey(HttpServletRequest request) {
    return request.getRemoteAddr();
  }
}
