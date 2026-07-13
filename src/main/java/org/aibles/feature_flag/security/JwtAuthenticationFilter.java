package org.aibles.feature_flag.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aibles.feature_flag.metrics.FeatureFlagMetrics;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  private final JwtTokenProvider tokenProvider;
  private final CustomUserDetailsService userDetailsService;
  private final FeatureFlagMetrics metrics;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String token = extractToken(request);

    if (StringUtils.hasText(token)) {
      if (tokenProvider.validateToken(token)) {
        try {
          String email = tokenProvider.getEmailFromToken(token);
          UserDetails userDetails = userDetailsService.loadUserByUsername(email);

          UsernamePasswordAuthenticationToken authentication =
              new UsernamePasswordAuthenticationToken(
                  userDetails, null, userDetails.getAuthorities());
          authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
          SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (UsernameNotFoundException ex) {
          metrics.recordAuthFailure(FeatureFlagMetrics.AuthFailure.ADMIN_UNKNOWN_SUBJECT);
          log.warn(
              "JWT valid but subject no longer exists — request proceeds unauthenticated: {}",
              ex.getMessage());
          // fall through unauthenticated → 403 at the authorization filter
        } catch (JwtException ex) {
          // Guards the TOCTOU window: validateToken() and getEmailFromToken() each parse
          // the token independently, so an expiry crossing between the two calls would
          // otherwise escape as 500.
          metrics.recordAuthFailure(FeatureFlagMetrics.AuthFailure.ADMIN_INVALID_TOKEN);
          log.debug(
              "JWT exception after initial validation (possible TOCTOU on expiry): {}",
              ex.getMessage());
        }
      } else {
        // A Bearer token was presented but failed validation (bad signature, expired, malformed).
        metrics.recordAuthFailure(FeatureFlagMetrics.AuthFailure.ADMIN_INVALID_TOKEN);
      }
    }

    filterChain.doFilter(request, response);
  }

  private String extractToken(HttpServletRequest request) {
    String bearerToken = request.getHeader("Authorization");
    if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
      return bearerToken.substring(7);
    }
    return null;
  }
}
