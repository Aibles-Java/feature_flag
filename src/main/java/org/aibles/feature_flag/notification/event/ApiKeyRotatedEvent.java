package org.aibles.feature_flag.notification.event;

import java.util.UUID;

/**
 * Published after an environment's API key is rotated. Never carries the plaintext key.
 *
 * <p>{@code environmentId} was added for issue #36: webhook subscriptions are per-environment, so
 * the dispatcher needs the id — the display names alone cannot identify which subscriptions to
 * notify.
 */
public record ApiKeyRotatedEvent(
    UUID environmentId, String environmentName, String projectName, String actorEmail) {}
