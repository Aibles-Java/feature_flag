package org.aibles.feature_flag.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Limits on a flag-state {@code value}, bound from {@code app.flag-value.*} (S-0.5, D-10).
 *
 * @param maxLength maximum length of a {@code value}, counted in characters (Java {@code
 *     String.length()}, i.e. UTF-16 code units), not bytes. Default 8192. Applies to PUT state and
 *     environment import.
 */
@ConfigurationProperties(prefix = "app.flag-value")
@Validated
public record FlagValueProperties(@DefaultValue("8192") @Min(1) int maxLength) {}
