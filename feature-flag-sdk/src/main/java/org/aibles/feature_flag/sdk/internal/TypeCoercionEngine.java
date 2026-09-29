package org.aibles.feature_flag.sdk.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.aibles.feature_flag.sdk.FlagValueType;
import org.aibles.feature_flag.sdk.exception.FlagTypeMismatchException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Static utility that coerces raw flag values into typed results.
 *
 * <p>Rules (OQ-07 / ADR-SDK-003):
 *
 * <ul>
 *   <li>When {@code enabled == false} the caller default is returned silently, regardless of type.
 *   <li>When {@code enabled == true} and the actual {@code valueType} does not match the requested
 *       type, {@link FlagTypeMismatchException} is thrown.
 *   <li>When a value is malformed (server declared INTEGER but value is corrupt, or JSON is
 *       unparseable) the SDK logs WARN with flag key only (never the raw value) and returns the
 *       caller default — it does NOT throw (ADR-SDK-003 never-throw-on-transient contract).
 *   <li>JSON values are deserialized with Jackson. Default typing is NEVER enabled (ADR-SDK-003
 *       polymorphic-deserialization safety). Value strings larger than 256 KB are capped: WARN
 *       logged (flag key only) and caller default returned.
 * </ul>
 */
public final class TypeCoercionEngine {

  private static final Logger log = LoggerFactory.getLogger(TypeCoercionEngine.class);

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
      // ADR-SDK-003: malformed value is an operational fault (server sent corrupt data).
      // Degrade gracefully — WARN with flag key only (never the raw value, never e.getMessage()
      // which would contain the value). Do NOT throw FlagTypeMismatchException.
      log.warn(
          "SDK flag [key] has valueType=INTEGER but value is unparseable — returning default."
              + " Flag key: [{}]",
          flagKey);
      return defaultValue;
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
      // ADR-SDK-003: cap breach is an operational fault. Degrade — WARN with flag key only.
      // Never include the raw value or its length in the log (DE-04 / HF-2).
      log.warn(
          "SDK flag [key] JSON value exceeds 256 KB cap (ADR-SDK-003) — returning default."
              + " Flag key: [{}]",
          flagKey);
      return defaultValue;
    }
    try {
      return MAPPER.readValue(value, targetClass);
    } catch (Exception e) {
      // ADR-SDK-003: malformed JSON is an operational fault (server sent corrupt data).
      // Degrade gracefully — WARN with flag key only. Never include e.getMessage() which
      // may contain the raw value (HF-2 / DE-04). Do NOT throw FlagTypeMismatchException.
      log.warn(
          "SDK flag [key] has valueType=JSON but value is unparseable — returning default."
              + " Flag key: [{}]",
          flagKey);
      return defaultValue;
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
