package org.aibles.feature_flag.notification.event;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Published when an active SDK API key crosses an expiry-warning threshold. Never carries the key
 * or its hash. {@code lastUsedAt} is {@code null} when the key has never authenticated; {@code
 * daysLeft} is the remaining lifetime rounded up to whole days.
 */
public record ApiKeyExpiringEvent(
    UUID environmentId,
    String environmentName,
    String projectName,
    UUID keyId,
    String keyName,
    String keyPrefix,
    LocalDateTime expiresAt,
    LocalDateTime lastUsedAt,
    long daysLeft) {}
