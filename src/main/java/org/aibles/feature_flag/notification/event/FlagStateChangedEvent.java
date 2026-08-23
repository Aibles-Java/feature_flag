package org.aibles.feature_flag.notification.event;

/** Published after a flag's per-environment state (enabled/value) is changed. */
public record FlagStateChangedEvent(
    String flagKey,
    String environmentName,
    String projectName,
    Boolean previousEnabled,
    Boolean newEnabled,
    String previousValue,
    String newValue,
    String actorEmail) {}
