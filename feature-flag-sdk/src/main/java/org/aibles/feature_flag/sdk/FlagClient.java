package org.aibles.feature_flag.sdk;

import org.aibles.feature_flag.sdk.internal.SdkConfig;

/**
 * Public API façade for the Feature Flag SDK.
 *
 * <p>This is a minimal stub for the G1 evidence gates. Full evaluation API (getBooleanValue,
 * getStringValue, etc.), caching, retry, and diagnostics are P4 deliverables.
 *
 * <p>Instances are thread-safe and should be shared as a singleton in the consuming application.
 * Create via {@link FlagClientBuilder}.
 */
public final class FlagClient implements AutoCloseable {

  private final SdkConfig config;

  FlagClient(SdkConfig config) {
    this.config = config;
  }

  SdkConfig getConfig() {
    return config;
  }

  @Override
  public void close() {
    // No background resources to release in the stub. Full P4 impl will
    // shut down the eviction scheduler and drain in-flight requests.
  }
}
