package org.aibles.feature_flag.sdk.internal;

/**
 * Test-only helper that exposes {@link SdkConfig.Builder#buildUnchecked()} to tests outside the
 * {@code org.aibles.feature_flag.sdk.internal} package.
 *
 * <p>{@code buildUnchecked()} is package-private (SF-4) to prevent external callers from bypassing
 * TLS enforcement. This class lives in {@code src/test} in the same package, making it accessible
 * to the package-private method. It re-exposes the method as {@code public} for test use only —
 * never ship this class in the production JAR.
 *
 * <p><strong>Production code MUST NOT reference this class.</strong>
 */
public final class TestSdkConfigHelper {

  private TestSdkConfigHelper() {}

  /**
   * Delegates to {@link SdkConfig.Builder#buildUnchecked()} (package-private). Use in unit tests
   * that need to point the SDK at a plain-HTTP loopback server.
   *
   * @param builder a configured builder
   * @return an {@link SdkConfig} with TLS scheme check skipped
   */
  public static SdkConfig buildUnchecked(SdkConfig.Builder builder) {
    return builder.buildUnchecked();
  }
}
