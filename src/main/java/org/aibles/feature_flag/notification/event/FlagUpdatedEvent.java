package org.aibles.feature_flag.notification.event;

import java.util.UUID;

/**
 * Published after a flag's metadata (name/description) changes (issue #36). Never reports a key
 * change — {@code FeatureFlag.key} is immutable.
 *
 * <p>Project-scoped, like {@link FlagCreatedEvent}.
 */
public record FlagUpdatedEvent(
    UUID projectId, String flagKey, String flagName, String description, String actorEmail) {}
