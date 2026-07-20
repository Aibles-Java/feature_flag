package org.aibles.feature_flag.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.UUID;
import org.aibles.feature_flag.config.JwtProperties;
import org.aibles.feature_flag.domain.entity.User;
import org.aibles.feature_flag.dto.request.LoginRequest;
import org.aibles.feature_flag.dto.request.LogoutRequest;
import org.aibles.feature_flag.dto.request.RefreshRequest;
import org.aibles.feature_flag.dto.request.RegisterRequest;
import org.aibles.feature_flag.dto.response.AuthResponse;
import org.aibles.feature_flag.exception.DuplicateResourceException;
import org.aibles.feature_flag.exception.UnauthorizedException;
import org.aibles.feature_flag.repository.UserRepository;
import org.aibles.feature_flag.security.JwtTokenProvider;
import org.aibles.feature_flag.security.UserPrincipal;
import org.aibles.feature_flag.service.RefreshTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

  @Mock UserRepository userRepository;
  @Mock PasswordEncoder passwordEncoder;
  @Mock AuthenticationManager authenticationManager;
  @Mock JwtTokenProvider jwtTokenProvider;
  @Mock RefreshTokenService refreshTokenService;
  @Mock JwtProperties jwtProperties;

  AuthServiceImpl authService;

  @BeforeEach
  void setUp() {
    authService =
        new AuthServiceImpl(
            userRepository,
            passwordEncoder,
            authenticationManager,
            jwtTokenProvider,
            refreshTokenService,
            jwtProperties);
  }

  @Test
  void register_throwsDuplicateResourceException_whenEmailAlreadyExists() {
    when(userRepository.existsByEmail("dup@example.com")).thenReturn(true);

    RegisterRequest req = new RegisterRequest();
    req.setEmail("dup@example.com");
    req.setPassword("password123");

    assertThatThrownBy(() -> authService.register(req))
        .isInstanceOf(DuplicateResourceException.class)
        .hasMessageContaining("dup@example.com");
    verify(userRepository, never()).save(any());
  }

  @Test
  void register_savesEncodedPassword_whenEmailIsNew() {
    when(userRepository.existsByEmail(any())).thenReturn(false);
    when(passwordEncoder.encode("rawpass")).thenReturn("$hashed");

    RegisterRequest req = new RegisterRequest();
    req.setEmail("new@example.com");
    req.setPassword("rawpass");
    req.setFirstName("Alice");
    req.setLastName("Smith");

    authService.register(req);

    verify(userRepository)
        .save(
            argThat(
                u ->
                    "new@example.com".equals(u.getEmail())
                        && "$hashed".equals(u.getPasswordHash())));
  }

  @Test
  void login_returnsTokenAndUserId_whenCredentialsAreValid() {
    UUID userId = UUID.randomUUID();
    User user = User.builder().id(userId).email("user@example.com").passwordHash("hash").build();
    UserPrincipal principal = UserPrincipal.from(user);
    Authentication auth =
        new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

    when(authenticationManager.authenticate(any())).thenReturn(auth);
    when(jwtTokenProvider.generateToken(principal)).thenReturn("jwt-token");
    when(refreshTokenService.issueNewFamily(userId)).thenReturn("refresh-token");
    when(jwtProperties.accessExpirationMs()).thenReturn(900_000L);

    LoginRequest req = new LoginRequest();
    req.setEmail("user@example.com");
    req.setPassword("rawpass");

    AuthResponse response = authService.login(req);

    assertThat(response.getAccessToken()).isEqualTo("jwt-token");
    assertThat(response.getRefreshToken()).isEqualTo("refresh-token");
    assertThat(response.getExpiresIn()).isEqualTo(900L);
    assertThat(response.getTokenType()).isEqualTo("Bearer");
    assertThat(response.getUserId()).isEqualTo(userId);
    assertThat(response.getEmail()).isEqualTo("user@example.com");
  }

  @Test
  void refreshRotatesAndMintsNewAccessToken() {
    UUID userId = UUID.randomUUID();
    User user = User.builder().id(userId).email("a@ex.com").passwordHash("x").build();
    when(refreshTokenService.rotate("old-refresh"))
        .thenReturn(new RefreshTokenService.RotationResult(userId, "new-refresh"));
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(jwtTokenProvider.generateToken(any())).thenReturn("new-jwt");
    when(jwtProperties.accessExpirationMs()).thenReturn(900_000L);

    RefreshRequest req = new RefreshRequest();
    req.setRefreshToken("old-refresh");

    AuthResponse res = authService.refresh(req);

    assertThat(res.getAccessToken()).isEqualTo("new-jwt");
    assertThat(res.getRefreshToken()).isEqualTo("new-refresh");
    assertThat(res.getUserId()).isEqualTo(userId);
    assertThat(res.getEmail()).isEqualTo("a@ex.com");
    assertThat(res.getExpiresIn()).isEqualTo(900L);
  }

  @Test
  void refreshPropagatesRotationFailure() {
    when(refreshTokenService.rotate("bad-refresh"))
        .thenThrow(new UnauthorizedException("Refresh token reuse detected"));

    RefreshRequest req = new RefreshRequest();
    req.setRefreshToken("bad-refresh");

    assertThatThrownBy(() -> authService.refresh(req)).isInstanceOf(UnauthorizedException.class);
    verify(jwtTokenProvider, never()).generateToken(any());
  }

  @Test
  void logoutDelegatesToRefreshTokenService() {
    LogoutRequest req = new LogoutRequest();
    req.setRefreshToken("some-refresh");

    authService.logout(req);

    verify(refreshTokenService).logout("some-refresh");
  }
}
