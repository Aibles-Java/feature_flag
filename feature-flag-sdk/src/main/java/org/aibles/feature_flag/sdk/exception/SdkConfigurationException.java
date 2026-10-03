package org.aibles.feature_flag.sdk.exception;

/**
 * Checked exception thrown at SDK build time when the supplied configuration is invalid. This is
 * the only checked exception in the SDK; all runtime failures use {@link SdkException} sub-types.
 *
 * <p>Callers MUST handle this at the {@code FlagClientBuilder.build()} call site to satisfy the
 * Java compiler — typically in service initialisation where an invalid config is a hard startup
 * error.
 */
public class SdkConfigurationException extends Exception {

  public SdkConfigurationException(String message) {
    super(message);
  }

  public SdkConfigurationException(String message, Throwable cause) {
    super(message, cause);
  }
}
