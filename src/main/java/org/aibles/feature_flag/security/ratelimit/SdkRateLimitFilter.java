package org.aibles.feature_flag.security.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.aibles.feature_flag.domain.entity.Environment;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Per-API-key rate limit on {@code /api/v1/sdk/**} (abuse protection, issue #26). Runs
 * <strong>after</strong> {@code ApiKeyAuthenticationFilter}, so the authenticated
 * {@link Environment} is already the SecurityContext principal — the bucket is keyed by its id,
 * which is stable across key rotation and unique per environment.
 */
public class SdkRateLimitFilter extends AbstractRateLimitFilter {

    public SdkRateLimitFilter(RateLimitService rateLimitService) {
        super(rateLimitService, RateLimitService.Scope.SDK);
    }

    @Override
    protected String resolveKey(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Environment env) {
            return env.getId().toString();
        }
        // Unauthenticated requests are already rejected (401) by the API-key filter before us;
        // if somehow unauthenticated, don't rate-limit (nothing to key on).
        return null;
    }
}
