package org.aibles.feature_flag.sdk.exception;

/**
 * Thrown when the caller requests a flag value as a specific type but the server-returned {@code
 * valueType} does not match. Only raised when the flag is enabled — a disabled flag returns the
 * caller-supplied default without a type check.
 *
 * <p><strong>Security note (SF-5 / DE-02):</strong> {@link #getMessage()} and {@link #getFlagKey()}
 * both expose the flag key, which may be CONFIDENTIAL-escalatable when the key encodes domain
 * semantics (e.g. {@code "payment-v2"}, {@code "fraud-check"}). Callers MUST NOT log this exception
 * at DEBUG or above in environments where flag-key confidentiality applies. Log at WARN with {@code
 * "[flag key redacted]"} substituted, or use {@code ex.getClass().getSimpleName()} only.
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
