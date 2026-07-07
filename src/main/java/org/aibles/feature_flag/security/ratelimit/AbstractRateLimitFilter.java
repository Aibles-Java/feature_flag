package org.aibles.feature_flag.security.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.TimeUnit;

/**
 * Base for the rate-limit filters (issue #26). Subclasses supply the scope and the key a request
 * is bucketed by; this class handles the enabled check, consumption, and emitting a
 * {@code 429 Too Many Requests} with a {@code Retry-After} header.
 *
 * <p>The 429 body is an RFC-7807 {@link ProblemDetail} matching the shape produced by
 * {@code GlobalExceptionHandler} (about:blank type, title, detail, instance). Because filters run
 * before Spring MVC, the response is written here directly rather than via {@code @RestControllerAdvice}
 * — the same approach the auth filters use for their 401s.
 */
public abstract class AbstractRateLimitFilter extends OncePerRequestFilter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    protected final RateLimitService rateLimitService;
    private final RateLimitService.Scope scope;

    protected AbstractRateLimitFilter(RateLimitService rateLimitService, RateLimitService.Scope scope) {
        this.rateLimitService = rateLimitService;
        this.scope = scope;
    }

    /** The bucket key for this request (e.g. client IP or environment id); {@code null} to skip limiting. */
    protected abstract String resolveKey(HttpServletRequest request);

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!rateLimitService.isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        String key = resolveKey(request);
        if (key == null) {
            filterChain.doFilter(request, response);
            return;
        }

        ConsumptionProbe probe = rateLimitService.tryConsume(scope, key);
        if (probe.isConsumed()) {
            filterChain.doFilter(request, response);
            return;
        }

        long retryAfterSeconds = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()));
        writeTooManyRequests(request, response, retryAfterSeconds);
    }

    private void writeTooManyRequests(HttpServletRequest request,
                                      HttpServletResponse response,
                                      long retryAfterSeconds) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        problem.setType(URI.create("about:blank"));
        problem.setTitle("Too Many Requests");
        problem.setDetail("Rate limit exceeded. Retry after " + retryAfterSeconds + " seconds.");
        problem.setInstance(URI.create(request.getRequestURI()));

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        MAPPER.writeValue(response.getWriter(), problem);
    }
}
