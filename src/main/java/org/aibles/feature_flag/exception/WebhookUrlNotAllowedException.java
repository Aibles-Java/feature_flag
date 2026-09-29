package org.aibles.feature_flag.exception;

/**
 * Thrown when a webhook URL is rejected by {@code SsrfGuard}. Mapped to 400 by {@code
 * GlobalExceptionHandler} so an operator subscribing a bad URL gets a clear validation error.
 */
public class WebhookUrlNotAllowedException extends RuntimeException {

  public WebhookUrlNotAllowedException(String message) {
    super(message);
  }
}
