package org.aibles.feature_flag.sdk.exception;

/**
 * Base unchecked exception for all SDK runtime errors. Callers may catch this super-type to handle
 * any SDK failure generically; prefer catching the specific sub-types for targeted handling.
 */
public class SdkException extends RuntimeException {

  public SdkException(String message) {
    super(message);
  }

  public SdkException(String message, Throwable cause) {
    super(message, cause);
  }
}
