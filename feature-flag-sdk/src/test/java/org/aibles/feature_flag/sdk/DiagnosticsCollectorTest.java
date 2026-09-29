package org.aibles.feature_flag.sdk;

import static org.junit.jupiter.api.Assertions.*;

import org.aibles.feature_flag.sdk.internal.DiagnosticsCollector;
import org.aibles.feature_flag.sdk.internal.DiagnosticsCollector.DiagnosticsSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DiagnosticsCollector}. */
class DiagnosticsCollectorTest {

  private DiagnosticsCollector collector;

  @BeforeEach
  void setUp() {
    collector = new DiagnosticsCollector();
  }

  @Test
  @DisplayName("Initial snapshot has all counters at 0 and hitRatio 0.0")
  void initialCountersAreZero() {
    DiagnosticsSnapshot snap = collector.snapshot();
    assertEquals(0, snap.serverErrors());
    assertEquals(0, snap.cacheHits());
    assertEquals(0, snap.cacheMisses());
    assertEquals(0, snap.invalidKeyEvents());
    assertEquals(0.0, snap.hitRatio());
  }

  @Test
  @DisplayName("recordServerError increments serverErrors counter")
  void recordServerErrorIncrementsCounter() {
    collector.recordServerError();
    collector.recordServerError();
    assertEquals(2, collector.snapshot().serverErrors());
  }

  @Test
  @DisplayName("recordCacheHit increments cacheHits counter")
  void recordCacheHitIncrementsCounter() {
    collector.recordCacheHit();
    assertEquals(1, collector.snapshot().cacheHits());
  }

  @Test
  @DisplayName("recordCacheMiss increments cacheMisses counter")
  void recordCacheMissIncrementsCounter() {
    collector.recordCacheMiss();
    collector.recordCacheMiss();
    collector.recordCacheMiss();
    assertEquals(3, collector.snapshot().cacheMisses());
  }

  @Test
  @DisplayName("recordInvalidKeyEvent increments invalidKeyEvents counter")
  void recordInvalidKeyEventIncrementsCounter() {
    collector.recordInvalidKeyEvent();
    assertEquals(1, collector.snapshot().invalidKeyEvents());
  }

  @Test
  @DisplayName("hitRatio returns cacheHits / (cacheHits + cacheMisses)")
  void hitRatioCalculation() {
    collector.recordCacheHit(); // 1 hit
    collector.recordCacheHit(); // 2 hits
    collector.recordCacheMiss(); // 1 miss
    double ratio = collector.snapshot().hitRatio();
    // 2 / 3 ≈ 0.667
    assertEquals(2.0 / 3.0, ratio, 1e-10);
  }

  @Test
  @DisplayName("hitRatio returns 0.0 when no lookups have been made")
  void hitRatioZeroWhenNoLookups() {
    assertEquals(0.0, collector.snapshot().hitRatio());
  }

  @Test
  @DisplayName("hitRatio returns 1.0 when all lookups are hits")
  void hitRatioOneWhenAllHits() {
    collector.recordCacheHit();
    collector.recordCacheHit();
    assertEquals(1.0, collector.snapshot().hitRatio(), 1e-10);
  }

  @Test
  @DisplayName("snapshot is immutable point-in-time — later increments do not affect earlier snap")
  void snapshotIsImmutable() {
    collector.recordCacheHit();
    DiagnosticsSnapshot snap = collector.snapshot();
    collector.recordCacheHit(); // after snapshot
    assertEquals(1, snap.cacheHits(), "Snapshot must not reflect post-snapshot increments");
  }
}
