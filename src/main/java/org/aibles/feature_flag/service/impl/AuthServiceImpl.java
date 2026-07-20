package org.aibles.feature_flag.service.impl;

import lombok.RequiredArgsConstructor;
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
import org.aibles.feature_flag.service.AuthService;
import org.aibles.feature_flag.service.RefreshTokenService;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final AuthenticationManager authenticationManager;
  private final JwtTokenProvider jwtTokenProvider;
  private final RefreshTokenService refreshTokenService;
  private final JwtProperties jwtProperties;

  @Override
  @Transactional
  public void register(RegisterRequest request) {
    if (userRepository.existsByEmail(request.getEmail())) {
      throw new DuplicateResourceException("Email already in use: " + request.getEmail());
    }
    User user =
        User.builder()
            .email(request.getEmail())
            .passwordHash(passwordEncoder.encode(request.getPassword()))
            .firstName(request.getFirstName())
            .lastName(request.getLastName())
            .build();
    userRepository.save(user);
  }

  @Override
  @Transactional
  public AuthResponse login(LoginRequest request) {
    Authentication authentication =
        authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword()));
    UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();

    String accessToken = jwtTokenProvider.generateToken(principal);
    String refreshToken = refreshTokenService.issueNewFamily(principal.getId());

    return AuthResponse.builder()
        .accessToken(accessToken)
        .refreshToken(refreshToken)
        .expiresIn(jwtProperties.accessExpirationMs() / 1000)
        .userId(principal.getId())
        .email(principal.getEmail())
        .build();
  }

  @Override
  @Transactional
  public AuthResponse refresh(RefreshRequest request) {
    RefreshTokenService.RotationResult rotation =
        refreshTokenService.rotate(request.getRefreshToken());
    User user =
        userRepository
            .findById(rotation.userId())
            .orElseThrow(() -> new UnauthorizedException("User no longer exists"));
    UserPrincipal principal = UserPrincipal.from(user);

    return AuthResponse.builder()
        .accessToken(jwtTokenProvider.generateToken(principal))
        .refreshToken(rotation.refreshToken())
        .expiresIn(jwtProperties.accessExpirationMs() / 1000)
        .userId(user.getId())
        .email(user.getEmail())
        .build();
  }

  @Override
  @Transactional
  public void logout(LogoutRequest request) {
    refreshTokenService.logout(request.getRefreshToken());
  }
}
