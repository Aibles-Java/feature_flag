package org.aibles.feature_flag.domain.enums;

/** Environment classification; the production-protection rule keys off {@code PRODUCTION}. */
public enum EnvType {
    DEVELOPMENT,
    STAGING,
    PRODUCTION
}
