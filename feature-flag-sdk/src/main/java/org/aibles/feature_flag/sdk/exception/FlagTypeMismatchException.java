package org.aibles.feature_flag.sdk.exception;

/**
 * Thrown when the caller requests a flag value as a specific type but the server-returned {@code
 * valueType} does not match. Only raised when the flag is enabled — a disabled flag returns the
 * caller-supplied default without a type check.
 */
public class FlagTypeMismatchException extends SdkException {

  private final String flagKey;

  public FlagTypeMismatchException(String flagKey, String expected, String actual) {
    super("Flag '" + flagKey + "': expected type " + expected + " but server returned " + actual);
    this.flagKey = flagKey;
  }

  public String getFlagKey() {
    return flagKey;
  }
}
