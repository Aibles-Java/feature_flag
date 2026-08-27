package org.aibles.feature_flag.config;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.aibles.feature_flag.metrics.FeatureFlagMetrics;
import org.aibles.feature_flag.repository.EnvironmentRepository;
import org.aibles.feature_flag.security.ApiKeyAuthenticationFilter;
import org.aibles.feature_flag.security.CustomUserDetailsService;
import org.aibles.feature_flag.security.JwtAuthenticationFilter;
import org.aibles.feature_flag.security.JwtTokenProvider;
import org.aibles.feature_flag.security.ProblemDetailAuthenticationEntryPoint;
import org.aibles.feature_flag.security.ratelimit.AuthRateLimitFilter;
import org.aibles.feature_flag.security.ratelimit.RateLimitProperties;
import org.aibles.feature_flag.security.ratelimit.RateLimitService;
import org.aibles.feature_flag.security.ratelimit.SdkRateLimitFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
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
  private final FeatureFlagMetrics metrics;

  /** Renders unauthenticated admin-chain requests as 401 problem+json (see the admin chain). */
  private final ProblemDetailAuthenticationEntryPoint authenticationEntryPoint =
      new ProblemDetailAuthenticationEntryPoint();

  // Chain 0: Actuator/management endpoints (issue #29). Highest precedence so /actuator/**
  // never falls through to the JWT admin chain. Health is public (liveness/readiness probes);
  // everything else — notably /actuator/prometheus — requires HTTP Basic against a dedicated,
  // isolated in-memory "metrics" scraper account. Deploy behind a network policy as well.
  @Bean
  @Order(0)
  public SecurityFilterChain managementFilterChain(
      HttpSecurity http,
      @Value("${app.metrics.username:metrics}") String metricsUsername,
      @Value("${app.metrics.password:}") String metricsPassword)
      throws Exception {

    DaoAuthenticationProvider metricsProvider =
        new DaoAuthenticationProvider(metricsUserDetailsService(metricsUsername, metricsPassword));

    // Matched against the fixed management base path (management.endpoints.web.base-path
    // defaults to /actuator, which we don't override) rather than EndpointRequest — the latter
    // moved modules in Boot 4.1; plain paths keep this chain dependency-light.
    http.securityMatcher("/actuator/**")
        .csrf(AbstractHttpConfigurer::disable)
        .cors(cors -> cors.configurationSource(corsConfigurationSource()))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/actuator/health", "/actuator/health/**")
                    .permitAll()
                    .anyRequest()
                    .hasRole("METRICS"))
        .authenticationProvider(metricsProvider)
        .httpBasic(Customizer.withDefaults());

    return http.build();
  }

  /**
   * A single in-memory scraper account, scoped to the management chain only (never registered
   * globally, so it can't authenticate against the app/JWT chains). Password is a plaintext shared
   * secret supplied via {@code app.metrics.password}; the delegating encoder's {@code {noop}}
   * prefix keeps it as-is — acceptable because the endpoint is additionally network-restricted.
   *
   * <p>When no password is configured the account is created <strong>disabled</strong>. This is
   * essential: {@code NoOpPasswordEncoder} treats an empty configured password as a valid match for
   * an empty presented password, so a blank secret would otherwise let anyone authenticate as
   * {@code metrics} with an empty password. A disabled account can never authenticate, so the
   * protected endpoints stay closed until an operator sets {@code APP_METRICS_PASSWORD}.
   */
  private InMemoryUserDetailsManager metricsUserDetailsService(String username, String password) {
    boolean noPassword = password == null || password.isEmpty();
    UserDetails scraper =
        User.withUsername(username)
            .password("{noop}" + (password == null ? "" : password))
            .roles("METRICS")
            .disabled(noPassword)
            .build();
    return new InMemoryUserDetailsManager(scraper);
  }

  // Chain 1: SDK endpoints — authenticated via API key header
  @Bean
  @Order(1)
  public SecurityFilterChain sdkFilterChain(HttpSecurity http) throws Exception {
    ApiKeyAuthenticationFilter apiKeyFilter =
        new ApiKeyAuthenticationFilter(environmentRepository, metrics);
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
        new JwtAuthenticationFilter(jwtTokenProvider, userDetailsService, metrics);
    AuthRateLimitFilter authRateLimitFilter = new AuthRateLimitFilter(rateLimitService);

    http.csrf(AbstractHttpConfigurer::disable)
        .cors(cors -> cors.configurationSource(corsConfigurationSource()))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth
                    // Note: /actuator/** (incl. the public health probes from #25) is owned by the
                    // higher-precedence managementFilterChain (@Order(0)) above, so it never
                    // reaches
                    // this chain — no actuator rule is needed or effective here.
                    .requestMatchers(
                        "/api/v1/auth/**", "/swagger-ui/**", "/swagger-ui.html", "/api-docs/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        // Without this, Spring Security defaults the entry point to Http403ForbiddenEntryPoint —
        // it only picks a 401-capable default when a built-in mechanism (form login, HTTP Basic)
        // is configured, and this chain authenticates with a custom JWT filter instead. The result
        // was that *unauthenticated* and expired-token requests returned 403, which clients cannot
        // tell apart from a genuine permission denial. Explicitly: 401 = who are you,
        // 403 = you may not.
        .exceptionHandling(ex -> ex.authenticationEntryPoint(authenticationEntryPoint))
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
