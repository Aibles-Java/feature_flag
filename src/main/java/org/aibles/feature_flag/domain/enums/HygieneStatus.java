package org.aibles.feature_flag.domain.enums;

/** Filter for the flag-hygiene report (issue #37). */
public enum HygieneStatus {
  /** Every active (flag, environment) pair in the project, with its hygiene fields. */
  ALL,
  /** Pairs not evaluated within {@code app.hygiene.stale-after}. */
  STALE,
  /** Pairs whose flag has passed its {@code expiresAt} date. */
  EXPIRED
}
