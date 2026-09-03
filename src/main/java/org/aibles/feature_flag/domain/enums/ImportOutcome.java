package org.aibles.feature_flag.domain.enums;

/** What an environment import did (or, in dry-run mode, would do) with one snapshot flag entry. */
public enum ImportOutcome {
  /** The flag and/or its state row did not exist in the target and was created. */
  CREATED,
  /** The state row existed with different values and was overwritten. */
  UPDATED,
  /** The state row already matched the snapshot — nothing to do. */
  UNCHANGED,
  /** A conflict the import refused to resolve (SKIP strategy, or a value-type mismatch). */
  SKIPPED
}
