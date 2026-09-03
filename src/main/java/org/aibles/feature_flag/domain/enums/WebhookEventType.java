package org.aibles.feature_flag.domain.enums;

/**
 * Event types a webhook subscription can listen for (issue #36).
 *
 * <p>Stored as {@code VARCHAR} via {@code @Enumerated(STRING)}, so the names are part of the
 * persisted contract as well as the API — rename a constant and existing rows stop matching.
 */
public enum WebhookEventType {

  /** A flag was created. Project-scoped, so it fans out to every environment in the project. */
  FLAG_CREATED,

  /** A flag's name/description changed (never its key, which is immutable). Project-scoped. */
  FLAG_UPDATED,

  /** A flag was archived or unarchived. Project-scoped. */
  FLAG_ARCHIVED,

  /** A flag's per-environment state (enabled/value/rollout) changed. Environment-scoped. */
  FLAG_STATE_CHANGED,

  /** An environment's SDK API key was rotated. Environment-scoped. Never carries the key. */
  API_KEY_ROTATED
}
