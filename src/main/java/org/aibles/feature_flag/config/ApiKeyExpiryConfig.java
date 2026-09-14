package org.aibles.feature_flag.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on Spring scheduling for the API key expiry scan only when warnings are enabled, so the
 * test profile — which disables them — runs no background scheduler thread.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
    prefix = "app.api-key.expiry-warning",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ApiKeyExpiryConfig {}
