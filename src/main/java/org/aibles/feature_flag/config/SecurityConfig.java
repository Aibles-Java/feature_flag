package org.aibles.feature_flag.config;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.aibles.feature_flag.repository.EnvironmentRepository;
import org.aibles.feature_flag.security.ApiKeyAuthenticationFilter;
import org.aibles.feature_flag.security.CustomUserDetailsService;
import org.aibles.feature_flag.security.JwtAuthenticationFilter;
import org.aibles.feature_flag.security.JwtTokenProvider;
import org.aibles.feature_flag.security.ratelimit.AuthRateLimitFilter;
import org.aibles.feature_flag.security.ratelimit.RateLimitProperties;
import org.aibles.feature_flag.security.ratelimit.RateLimitService;
import org.aibles.feature_flag.security.ratelimit.SdkRateLimitFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(RateLimitProperties.class)
@RequiredArgsConstructor
public class SecurityConfig {

  private final JwtTokenProvider jwtTokenProvider;
  private final CustomUserDetailsService userDetailsService;
  private final EnvironmentRepository environmentRepository;
  private final RateLimitService rateLimitService;

  // Chain 1: SDK endpoints — authenticated via API key header
  @Bean
  @Order(1)
  public SecurityFilterChain sdkFilterChain(HttpSecurity http) throws Exception {
    ApiKeyAuthenticationFilter apiKeyFilter = new ApiKeyAuthenticationFilter(environmentRepository);
    SdkRateLimitFilter sdkRateLimitFilter = new SdkRateLimitFilter(rateLimitService);

    http.securityMatcher("/api/v1/sdk/**")
        .csrf(AbstractHttpConfigurer::disable)
        .cors(cors -> cors.configurationSource(corsConfigurationSource()))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        .addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class)
        // Anchor after the standard UsernamePasswordAuthenticationFilter, which sits after
        // apiKeyFilter — so the Environment principal is already resolved when we key the limiter.
        .addFilterAfter(sdkRateLimitFilter, UsernamePasswordAuthenticationFilter.class);

    return http.build();
  }

  // Chain 2: Admin endpoints — authenticated via JWT Bearer token
  @Bean
  @Order(2)
  public SecurityFilterChain adminFilterChain(HttpSecurity http) throws Exception {
    JwtAuthenticationFilter jwtFilter =
        new JwtAuthenticationFilter(jwtTokenProvider, userDetailsService);
    AuthRateLimitFilter authRateLimitFilter = new AuthRateLimitFilter(rateLimitService);

    http.csrf(AbstractHttpConfigurer::disable)
        .cors(cors -> cors.configurationSource(corsConfigurationSource()))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth
                    // Ops health probes (liveness/readiness) must be reachable anonymously by
                    // load balancers / k8s / docker HEALTHCHECK. All other actuator endpoints
                    // fall through to authenticated() below, so they are not anonymously exposed.
                    .requestMatchers("/actuator/health/**")
                    .permitAll()
                    .requestMatchers(
                        "/api/v1/auth/**", "/swagger-ui/**", "/swagger-ui.html", "/api-docs/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        // Per-IP throttle on the (permitAll) /api/v1/auth/** endpoints. Anchored on the
        // standard UsernamePasswordAuthenticationFilter (added before jwtFilter so it runs first).
        .addFilterBefore(authRateLimitFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

    return http.build();
  }

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  public DaoAuthenticationProvider authenticationProvider() {
    DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
    provider.setPasswordEncoder(passwordEncoder());
    return provider;
  }

  @Bean
  public AuthenticationManager authenticationManager(AuthenticationConfiguration config)
      throws Exception {
    return config.getAuthenticationManager();
  }

  @Bean
  public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(List.of("http://localhost:5173", "http://localhost:5174"));
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of("*"));
    config.setAllowCredentials(true);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return source;
  }
}
