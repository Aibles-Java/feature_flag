package org.aibles.feature_flag.sdk.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.aibles.feature_flag.sdk.FlagValueType;
import org.aibles.feature_flag.sdk.exception.FlagTypeMismatchException;

/**
 * Static utility that coerces raw flag values into typed results.
 *
 * <p>Rules (OQ-07 / ADR-SDK-003):
 *
 * <ul>
 *   <li>When {@code enabled == false} the caller default is returned silently, regardless of type.
 *   <li>When {@code enabled == true} and the actual {@code valueType} does not match the requested
 *       type, {@link FlagTypeMismatchException} is thrown.
 *   <li>JSON values are deserialized with Jackson. Default typing is NEVER enabled (ADR-SDK-003
 *       polymorphic-deserialization safety). Value strings larger than 256 KB are rejected.
 * </ul>
 */
public final class TypeCoercionEngine {

  /** 256 KB cap on JSON value strings (ADR-SDK-003). */
  public static final int JSON_VALUE_MAX_BYTES = 256 * 1024;

  /**
   * Shared mapper — configured once; MUST NOT enable default typing. The instance is thread-safe
   * once constructed.
   */
  private static final ObjectMapper MAPPER;

  static {
    MAPPER = new ObjectMapper();
    // Default typing is OFF by default in Jackson, but we state it explicitly for clarity and to
    // prevent a future maintainer from adding it (ADR-SDK-003 safety guard).
    MAPPER.deactivateDefaultTyping();
  }

  private TypeCoercionEngine() {}

  // ---------------------------------------------------------------------------
  // Boolean
  // ---------------------------------------------------------------------------

  /**
   * Returns the flag value as a {@code boolean}.
   *
   * @param flagKey key used in exception messages (never logged)
   * @param enabled whether the flag is enabled
   * @param value raw string value from the server
   * @param valueType server-reported type
   * @param defaultValue caller default returned when {@code enabled == false}
   */
  public static boolean coerceBoolean(
      String flagKey,
      boolean enabled,
      String value,
      FlagValueType valueType,
      boolean defaultValue) {
    if (!enabled) {
      return defaultValue;
    }
    assertType(flagKey, FlagValueType.BOOLEAN, valueType);
    return Boolean.parseBoolean(value);
  }

  // ---------------------------------------------------------------------------
  // String
  // ---------------------------------------------------------------------------

  /**
   * Returns the flag value as a {@link String}.
   *
   * @param defaultValue caller default returned when {@code enabled == false}
   */
  public static String coerceString(
      String flagKey, boolean enabled, String value, FlagValueType valueType, String defaultValue) {
    if (!enabled) {
      return defaultValue;
    }
    assertType(flagKey, FlagValueType.STRING, valueType);
    return value;
  }

  // ---------------------------------------------------------------------------
  // Integer
  // ---------------------------------------------------------------------------

  /**
   * Returns the flag value as an {@code int}.
   *
   * @param defaultValue caller default returned when {@code enabled == false}
   */
  public static int coerceInteger(
      String flagKey, boolean enabled, String value, FlagValueType valueType, int defaultValue) {
    if (!enabled) {
      return defaultValue;
    }
    assertType(flagKey, FlagValueType.INTEGER, valueType);
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw new FlagTypeMismatchException(
          flagKey, FlagValueType.INTEGER.name(), "unparseable: " + e.getMessage());
    }
  }

  // ---------------------------------------------------------------------------
  // JSON
  // ---------------------------------------------------------------------------

  /**
   * Deserializes the flag value as JSON into the given target class.
   *
   * <p>ADR-SDK-003 guards: no default typing; value size capped at 256 KB.
   *
   * @param targetClass the type to deserialize into
   * @param defaultValue caller default returned when {@code enabled == false}
   */
  public static <T> T coerceJson(
      String flagKey,
      boolean enabled,
      String value,
      FlagValueType valueType,
      Class<T> targetClass,
      T defaultValue) {
    if (!enabled) {
      return defaultValue;
    }
    assertType(flagKey, FlagValueType.JSON, valueType);
    if (value != null
        && value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > JSON_VALUE_MAX_BYTES) {
      throw new FlagTypeMismatchException(
          flagKey, FlagValueType.JSON.name(), "value exceeds 256 KB cap (ADR-SDK-003)");
    }
    try {
      return MAPPER.readValue(value, targetClass);
    } catch (Exception e) {
      throw new FlagTypeMismatchException(
          flagKey, FlagValueType.JSON.name(), "JSON parse error: " + e.getMessage());
    }
  }

  // ---------------------------------------------------------------------------
  // Shared type-guard
  // ---------------------------------------------------------------------------

  /**
   * Asserts that the actual value type matches the expected type. Throws {@link
   * FlagTypeMismatchException} on mismatch or when {@code actual} is null (unknown type).
   */
  static void assertType(String flagKey, FlagValueType expected, FlagValueType actual) {
    if (actual == null || actual != expected) {
      String actualName = actual == null ? "null" : actual.name();
      throw new FlagTypeMismatchException(flagKey, expected.name(), actualName);
    }
  }
}
