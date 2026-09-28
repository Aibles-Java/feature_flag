package org.aibles.feature_flag.sdk.exception;

/**
 * Thrown when the flag server returns HTTP 401, indicating the configured API key has been rejected
 * (e.g. it was rotated). This exception is unchecked; callers should wire the {@code onAuthFailure}
 * hook on {@link org.aibles.feature_flag.sdk.FlagClientBuilder} for a first-class alert path rather
 * than relying solely on try/catch.
 *
 * <p>The message MUST NOT contain the raw API key value.
 */
public class InvalidApiKeyException extends SdkException {

  public InvalidApiKeyException(String message) {
    super(message);
  }

  public InvalidApiKeyException(String message, Throwable cause) {
    super(message, cause);
  }
}
