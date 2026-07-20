package org.aibles.feature_flag.service;

import java.util.UUID;

public interface RefreshTokenService {

  /** Starts a new rotation family for the user; returns the plaintext refresh token. */
  String issueNewFamily(UUID userId);

  /** Validates + rotates a presented token. Throws UnauthorizedException on any invalid case. */
  RotationResult rotate(String presentedToken);

  /** Revokes the family of the presented token. Idempotent; never throws on unknown token. */
  void logout(String presentedToken);

  record RotationResult(UUID userId, String refreshToken) {}
}
