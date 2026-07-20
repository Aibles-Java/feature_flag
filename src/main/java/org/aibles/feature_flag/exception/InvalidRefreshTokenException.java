package org.aibles.feature_flag.exception;

/**
 * An authentication failure on the refresh-token flow: unknown, expired, revoked, or replayed
 * token. Distinct from {@link UnauthorizedException}, which means "authenticated but not allowed"
 * and maps to 403 — a client whose refresh token is dead needs 401 to know it must log in again.
 *
 * <p>The message is deliberately coarse at the API boundary: callers should not be able to tell a
 * replayed token from an expired one.
 */
public class InvalidRefreshTokenException extends RuntimeException {
  public InvalidRefreshTokenException(String message) {
    super(message);
  }
}
