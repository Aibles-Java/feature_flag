package org.aibles.feature_flag.notification.event;

/** Published after an environment's API key is rotated. Never carries the plaintext key. */
public record ApiKeyRotatedEvent(String environmentName, String projectName, String actorEmail) {}
