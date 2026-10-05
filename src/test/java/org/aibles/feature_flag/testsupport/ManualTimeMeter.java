package org.aibles.feature_flag.testsupport;

import io.github.bucket4j.TimeMeter;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/** Controllable time source for deterministic rate-limit tests (no sleeps). */
public final class ManualTimeMeter implements TimeMeter {

  private final AtomicLong nanos = new AtomicLong(1_000_000_000L);

  public void advance(Duration d) {
    nanos.addAndGet(d.toNanos());
  }

  @Override
  public long currentTimeNanos() {
    return nanos.get();
  }

  @Override
  public boolean isWallClockBased() {
    return false;
  }
}
