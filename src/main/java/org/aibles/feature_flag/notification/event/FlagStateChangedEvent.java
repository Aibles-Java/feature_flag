package org.aibles.feature_flag.notification.event;

import java.util.UUID;

/**
 * Published after a flag's per-environment state (enabled/value) is changed.
 *
 * <p>{@code environmentId} was added for issue #36 — webhook subscriptions are keyed by
 * environment.
 */
public record FlagStateChangedEvent(
    UUID environmentId,
    String flagKey,
    String environmentName,
    String projectName,
    Boolean previousEnabled,
    Boolean newEnabled,
    String previousValue,
    String newValue,
    String actorEmail) {}
