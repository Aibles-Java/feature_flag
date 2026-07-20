package org.aibles.feature_flag.exception;

/**
 * An authentication failure on the refresh-token flow: unknown, expired, revoked, or replayed
 * token. Distinct from {@link UnauthorizedException}, which means "authenticated but not allowed"
 * and maps to 403 — a client whose refresh token is dead needs 401 to know it must log in again.
 *
 * <p>The message carries the specific reason (reuse detected, expired, revoked, disabled account)
 * for the server-side log only. {@code GlobalExceptionHandler.handleInvalidRefreshToken} replaces
 * it with a single coarse detail at the API boundary, so callers cannot probe the endpoint to tell
 * a replayed token from an expired one.
 */
public class InvalidRefreshTokenException extends RuntimeException {
  public InvalidRefreshTokenException(String message) {
    super(message);
  }
}
