package org.aibles.feature_flag.notification.event;

import java.util.UUID;

/**
 * Published after a flag is archived ({@code archived=true}) or unarchived ({@code
 * archived=false}).
 *
 * <p>Project-scoped: a flag belongs to a project, not an environment, so issue #36's webhook
 * dispatcher fans this out to every environment in {@code projectId}.
 */
public record FlagArchivedEvent(
    UUID projectId, String flagKey, String projectName, boolean archived, String actorEmail) {}
