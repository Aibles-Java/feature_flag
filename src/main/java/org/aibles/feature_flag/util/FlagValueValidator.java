package org.aibles.feature_flag.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aibles.feature_flag.domain.enums.FlagValueType;
import org.aibles.feature_flag.exception.InvalidRequestException;

/**
 * Single shared check that a flag-state {@code value} parses according to the flag's {@link
 * FlagValueType} (F19). Used by the PUT state path and reused by environment import (S-0.6).
 *
 * <ul>
 *   <li>{@code null} is always accepted (no value);
 *   <li>BOOLEAN: exactly {@code "true"} or {@code "false"};
 *   <li>INTEGER: {@link Long#parseLong(String)};
 *   <li>JSON: one complete, well-formed JSON document;
 *   <li>STRING: anything. The length limit is a separate concern (S-0.5).
 * </ul>
 *
 * The error message never echoes the value, which may be mistakenly sensitive.
 */
public final class FlagValueValidator {

  private static final ObjectMapper JSON =
      new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private FlagValueValidator() {}

  /**
   * @throws InvalidRequestException (HTTP 400) when {@code value} does not match {@code type}.
   */
  public static void validate(FlagValueType type, String value) {
    if (value == null) {
      return;
    }
    if (!isValid(type, value)) {
      throw new InvalidRequestException("value is not a valid " + type + " for this flag");
    }
  }

  /**
   * Non-throwing variant, for callers that report the failure differently (e.g. import SKIPPED).
   */
  public static boolean isValid(FlagValueType type, String value) {
    if (value == null) {
      return true;
    }
    return switch (type) {
      case BOOLEAN -> value.equals("true") || value.equals("false");
      case INTEGER -> isLong(value);
      case JSON -> isJson(value);
      case STRING -> true;
    };
  }

  private static boolean isLong(String value) {
    try {
      Long.parseLong(value);
      return true;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  private static boolean isJson(String value) {
    try {
      JsonNode node = JSON.readTree(value);
      return node != null && !node.isMissingNode();
    } catch (JsonProcessingException e) {
      return false;
    }
  }
}
