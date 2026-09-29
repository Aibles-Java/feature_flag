package org.aibles.feature_flag.service;

import java.util.UUID;
import org.aibles.feature_flag.domain.enums.FlagValueType;

/**
 * One flag's state in one environment, as cached.
 *
 * <p>Holds the state <em>before</em> rollout is applied (ADR-0004): rollout is evaluated per
 * request on top of this, so one entry serves every identifier. Caching the evaluated response
 * instead would serve the first caller's outcome to everyone.
 *
 * <p>{@code flagId} is carried even though nothing in the response needs it, because the hygiene
 * tracker (issue #37) stamps usage by flag id and runs on the evaluation path — on cache hits
 * included. Without it a hit would have to re-query the flag just to learn its id, which is the
 * database round trip the cache exists to avoid.
 */
public record FlagStateSnapshot(
    UUID flagId,
    String flagKey,
    boolean enabled,
    String value,
    FlagValueType valueType,
    int rolloutPercent) {}
