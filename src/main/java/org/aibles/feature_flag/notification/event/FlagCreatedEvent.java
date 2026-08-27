package org.aibles.feature_flag.notification.event;

import java.util.UUID;
import org.aibles.feature_flag.domain.enums.FlagValueType;

/**
 * Published after a flag is created (issue #36).
 *
 * <p>Project-scoped: creating a flag auto-creates a {@code FlagEnvironmentState} for every existing
 * environment, so the webhook dispatcher fans this out to every environment in {@code projectId}.
 */
public record FlagCreatedEvent(
    UUID projectId, String flagKey, String flagName, FlagValueType valueType, String actorEmail) {}
