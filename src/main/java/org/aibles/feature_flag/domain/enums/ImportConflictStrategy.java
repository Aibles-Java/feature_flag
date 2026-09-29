package org.aibles.feature_flag.domain.enums;

/**
 * How an environment import treats a flag whose target state already differs from the snapshot
 * (issue #38). Missing flags are always created regardless of the strategy — a strategy only
 * arbitrates genuine conflicts.
 */
public enum ImportConflictStrategy {
  /** Leave the existing state untouched and report the flag as skipped. */
  SKIP,
  /** Replace the existing state with the snapshot's state. */
  OVERWRITE
}
