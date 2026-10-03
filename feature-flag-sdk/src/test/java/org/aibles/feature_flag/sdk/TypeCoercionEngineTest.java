package org.aibles.feature_flag.sdk;

import static org.junit.jupiter.api.Assertions.*;

import org.aibles.feature_flag.sdk.exception.FlagTypeMismatchException;
import org.aibles.feature_flag.sdk.internal.TypeCoercionEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link TypeCoercionEngine}. */
class TypeCoercionEngineTest {

  // ---------------------------------------------------------------------------
  // Boolean coercion
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("coerceBoolean returns true when enabled=true, valueType=BOOLEAN, value='true'")
  void coerceBooleanTrue() {
    boolean result =
        TypeCoercionEngine.coerceBoolean("flag-a", true, "true", FlagValueType.BOOLEAN, false);
    assertTrue(result);
  }

  @Test
  @DisplayName("coerceBoolean returns false when enabled=true, valueType=BOOLEAN, value='false'")
  void coerceBooleanFalse() {
    boolean result =
        TypeCoercionEngine.coerceBoolean("flag-a", true, "false", FlagValueType.BOOLEAN, true);
    assertFalse(result);
  }

  @Test
  @DisplayName("coerceBoolean returns caller default silently when enabled=false (OQ-07)")
  void coerceBooleanDisabledReturnsDefault() {
    // Type mismatch (STRING vs BOOLEAN) is irrelevant when disabled.
    boolean result =
        TypeCoercionEngine.coerceBoolean("flag-b", false, "wrong", FlagValueType.STRING, true);
    assertTrue(result);
  }

  @Test
  @DisplayName("coerceBoolean throws FlagTypeMismatchException when enabled=true, type=STRING")
  void coerceBooleanThrowsOnTypeMismatch() {
    assertThrows(
        FlagTypeMismatchException.class,
        () ->
            TypeCoercionEngine.coerceBoolean("flag-c", true, "hello", FlagValueType.STRING, false));
  }

  @Test
  @DisplayName("coerceBoolean throws FlagTypeMismatchException when valueType is null (unknown)")
  void coerceBooleanThrowsOnNullType() {
    assertThrows(
        FlagTypeMismatchException.class,
        () -> TypeCoercionEngine.coerceBoolean("flag-d", true, "true", null, false));
  }

  // ---------------------------------------------------------------------------
  // String coercion
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("coerceString returns value when enabled=true, valueType=STRING")
  void coerceStringHappyPath() {
    String result =
        TypeCoercionEngine.coerceString("flag-e", true, "hello", FlagValueType.STRING, "default");
    assertEquals("hello", result);
  }

  @Test
  @DisplayName("coerceString returns caller default when enabled=false (OQ-07)")
  void coerceStringDisabledReturnsDefault() {
    String result =
        TypeCoercionEngine.coerceString(
            "flag-f", false, "server-val", FlagValueType.INTEGER, "def");
    assertEquals("def", result);
  }

  @Test
  @DisplayName("coerceString throws FlagTypeMismatchException when type=INTEGER and enabled=true")
  void coerceStringThrowsOnTypeMismatch() {
    assertThrows(
        FlagTypeMismatchException.class,
        () ->
            TypeCoercionEngine.coerceString(
                "flag-g", true, "42", FlagValueType.INTEGER, "default"));
  }

  // ---------------------------------------------------------------------------
  // Integer coercion
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("coerceInteger returns integer value when enabled=true, valueType=INTEGER")
  void coerceIntegerHappyPath() {
    int result = TypeCoercionEngine.coerceInteger("flag-h", true, "42", FlagValueType.INTEGER, 0);
    assertEquals(42, result);
  }

  @Test
  @DisplayName("coerceInteger returns caller default when enabled=false (OQ-07)")
  void coerceIntegerDisabledReturnsDefault() {
    int result =
        TypeCoercionEngine.coerceInteger("flag-i", false, "not-an-int", FlagValueType.STRING, 99);
    assertEquals(99, result);
  }

  @Test
  @DisplayName("coerceInteger throws FlagTypeMismatchException when type=STRING and enabled=true")
  void coerceIntegerThrowsOnTypeMismatch() {
    assertThrows(
        FlagTypeMismatchException.class,
        () -> TypeCoercionEngine.coerceInteger("flag-j", true, "hello", FlagValueType.STRING, 0));
  }

  // ---------------------------------------------------------------------------
  // JSON coercion
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("coerceJson deserializes JSON into Map when enabled=true")
  void coerceJsonHappyPath() {
    String json = "{\"limit\":5}";
    java.util.Map<?, ?> result =
        TypeCoercionEngine.coerceJson(
            "flag-k", true, json, FlagValueType.JSON, java.util.Map.class, null);
    assertNotNull(result);
    assertEquals(5, result.get("limit"));
  }

  @Test
  @DisplayName("coerceJson returns caller default when enabled=false (OQ-07)")
  void coerceJsonDisabledReturnsDefault() {
    java.util.Map<?, ?> defaultVal = java.util.Map.of("x", 1);
    java.util.Map<?, ?> result =
        TypeCoercionEngine.coerceJson(
            "flag-l", false, "invalid", FlagValueType.STRING, java.util.Map.class, defaultVal);
    assertSame(defaultVal, result);
  }

  @Test
  @DisplayName("coerceJson throws FlagTypeMismatchException when type=STRING and enabled=true")
  void coerceJsonThrowsOnTypeMismatch() {
    assertThrows(
        FlagTypeMismatchException.class,
        () ->
            TypeCoercionEngine.coerceJson(
                "flag-m", true, "{}", FlagValueType.STRING, java.util.Map.class, null));
  }

  @Test
  @DisplayName("coerceJson: Jackson ObjectMapper must NOT enable default typing (ADR-SDK-003)")
  void coerceJsonNoDefaultTyping() {
    // If default typing were enabled, a malicious JSON array ["class.Name", {...}] would
    // trigger class instantiation. This test passes a type-array payload and asserts no
    // polymorphic instantiation occurs — it must parse as a plain List, not trigger reflection.
    String maliciousLike = "[\"java.lang.String\", \"value\"]";
    // Should successfully deserialize as a plain List (no polymorphic lookup)
    java.util.List<?> result =
        TypeCoercionEngine.coerceJson(
            "flag-n", true, maliciousLike, FlagValueType.JSON, java.util.List.class, null);
    assertNotNull(result);
    assertEquals("java.lang.String", result.get(0));
    assertEquals("value", result.get(1));
  }

  @Test
  @DisplayName(
      "coerceJson degrades to caller default (no throw) when value exceeds 256 KB cap (HF-3/ADR-SDK-003)")
  void coerceJsonRejectsOversizedValue() {
    // Per ADR-SDK-003 an over-cap value is an operational fault (DoS guard), so the SDK degrades to
    // the caller default rather than throwing — matching the malformed-JSON policy and the
    // never-throw-on-non-caller-error contract.
    String bigValue = "\"" + "x".repeat(TypeCoercionEngine.JSON_VALUE_MAX_BYTES + 1) + "\"";
    String fallback = "DEFAULT";
    String result =
        TypeCoercionEngine.coerceJson(
            "flag-o", true, bigValue, FlagValueType.JSON, String.class, fallback);
    assertEquals(fallback, result, "over-cap JSON value must return the caller default, not throw");
  }
}
