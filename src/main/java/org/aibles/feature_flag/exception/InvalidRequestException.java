package org.aibles.feature_flag.exception;

/**
 * A syntactically valid request whose <em>content</em> cannot be accepted — e.g. an environment
 * snapshot carrying an unsupported {@code schemaVersion} (issue #38). Maps to HTTP 400; use {@link
 * DuplicateResourceException} (409) for uniqueness clashes and {@link UnauthorizedException} (403)
 * for permission failures.
 */
public class InvalidRequestException extends RuntimeException {
  public InvalidRequestException(String message) {
    super(message);
  }
}
