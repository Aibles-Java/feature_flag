package org.aibles.feature_flag.service;

import org.aibles.feature_flag.domain.enums.FlagValueType;

public record FlagStateSnapshot(
    String flagKey, boolean enabled, String value, FlagValueType valueType, int rolloutPercent) {}
