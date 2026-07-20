package org.aibles.feature_flag.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.aibles.feature_flag.config.JwtProperties;
import org.aibles.feature_flag.domain.entity.RefreshToken;
import org.aibles.feature_flag.domain.entity.User;
import org.aibles.feature_flag.exception.UnauthorizedException;
import org.aibles.feature_flag.repository.RefreshTokenRepository;
import org.aibles.feature_flag.repository.UserRepository;
import org.aibles.feature_flag.service.RefreshTokenService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RefreshTokenServiceImpl implements RefreshTokenService {

  private static final SecureRandom RANDOM = new SecureRandom();

  private final RefreshTokenRepository repository;
  private final UserRepository userRepository;
  private final JwtProperties jwtProperties;

  @Override
  @Transactional
  public String issueNewFamily(UUID userId) {
    return createRow(userId, UUID.randomUUID());
  }

  /**
   * Every rejection path here reports failure by throwing, and two of them revoke the family first.
   * Spring rolls back on unchecked exceptions by default, which would undo the revoke the throw is
   * meant to announce — reuse detection would silently do nothing. {@code noRollbackFor} keeps the
   * revoke committed. Nothing on these paths should be rolled back: {@code consume} either did not
   * match a row or belongs to a family we are revoking anyway.
   */
  @Override
  @Transactional(noRollbackFor = UnauthorizedException.class)
  public RotationResult rotate(String presentedToken) {
    RefreshToken row =
        repository
            .findByTokenHash(hash(presentedToken))
            .orElseThrow(() -> new UnauthorizedException("Invalid refresh token"));

    LocalDateTime now = LocalDateTime.now();

    if (row.getRevokedAt() != null) {
      throw new UnauthorizedException("Refresh token has been revoked");
    }
    if (row.getExpiresAt().isBefore(now)) {
      throw new UnauthorizedException("Refresh token has expired");
    }
    if (row.getRotatedAt() != null) {
      // A consumed token was replayed — the family is compromised.
      repository.revokeFamily(row.getFamilyId(), now);
      throw new UnauthorizedException("Refresh token reuse detected");
    }

    // Atomically consume. A concurrent request that already consumed it wins; we lose and
    // treat the loss as a reuse signal, revoking the family.
    if (repository.consume(row.getId(), now) != 1) {
      repository.revokeFamily(row.getFamilyId(), now);
      throw new UnauthorizedException("Refresh token reuse detected");
    }

    User user =
        userRepository
            .findById(row.getUserId())
            .orElseThrow(() -> new UnauthorizedException("User no longer exists"));
    if (!user.isEnabled()) {
      repository.revokeFamily(row.getFamilyId(), now);
      throw new UnauthorizedException("Account is disabled");
    }

    String newToken = createRow(row.getUserId(), row.getFamilyId());
    return new RotationResult(row.getUserId(), newToken);
  }

  @Override
  @Transactional
  public void logout(String presentedToken) {
    repository
        .findByTokenHash(hash(presentedToken))
        .ifPresent(row -> repository.revokeFamily(row.getFamilyId(), LocalDateTime.now()));
  }

  private String createRow(UUID userId, UUID familyId) {
    String token = generateToken();
    RefreshToken row =
        RefreshToken.builder()
            .userId(userId)
            .familyId(familyId)
            .tokenHash(hash(token))
            .expiresAt(
                LocalDateTime.now().plus(jwtProperties.refreshExpirationMs(), ChronoUnit.MILLIS))
            .build();
    repository.save(row);
    return token;
  }

  private static String generateToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return HexFormat.of().formatHex(bytes); // 64-char hex, 256-bit
  }

  /** Unsalted SHA-256 hex — sufficient for a 256-bit random token, keeps lookup O(1). */
  static String hash(String token) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}
