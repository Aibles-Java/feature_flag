package org.aibles.feature_flag.notification.event;

/**
 * Published after a flag is archived ({@code archived=true}) or unarchived ({@code
 * archived=false}).
 */
public record FlagArchivedEvent(
    String flagKey, String projectName, boolean archived, String actorEmail) {}
