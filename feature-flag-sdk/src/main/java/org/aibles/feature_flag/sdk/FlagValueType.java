package org.aibles.feature_flag.sdk;

/**
 * Value-type discriminator returned by the flag server in {@code valueType} field. Drives
 * TypeCoercionEngine dispatch and FlagTypeMismatchException messaging.
 */
public enum FlagValueType {
  BOOLEAN,
  STRING,
  INTEGER,
  JSON
}
