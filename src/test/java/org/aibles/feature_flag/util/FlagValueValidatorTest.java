package org.aibles.feature_flag.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.aibles.feature_flag.domain.enums.FlagValueType;
import org.aibles.feature_flag.exception.InvalidRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** S-0.4 (F19, T-F19-1): the shared value-by-valueType validator. */
class FlagValueValidatorTest {

  @ParameterizedTest
  @EnumSource(FlagValueType.class)
  void nullIsAlwaysAccepted(FlagValueType type) {
    assertThatCode(() -> FlagValueValidator.validate(type, null)).doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(strings = {"true", "false"})
  void boolean_acceptsTrueAndFalse(String v) {
    assertThatCode(() -> FlagValueValidator.validate(FlagValueType.BOOLEAN, v))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(strings = {"yes", "TRUE", "True", "1", "", " true", "null"})
  void boolean_rejectsOthers(String v) {
    assertThatThrownBy(() -> FlagValueValidator.validate(FlagValueType.BOOLEAN, v))
        .isInstanceOf(InvalidRequestException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"42", "-7", "0", "9223372036854775807"})
  void integer_acceptsLongs(String v) {
    assertThatCode(() -> FlagValueValidator.validate(FlagValueType.INTEGER, v))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(strings = {"abc", "", "1.5", "9223372036854775808", " 1", "1e3"})
  void integer_rejectsOthers(String v) {
    assertThatThrownBy(() -> FlagValueValidator.validate(FlagValueType.INTEGER, v))
        .isInstanceOf(InvalidRequestException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"{\"a\":1}", "[1,2]", "\"s\"", "123", "true", "null", "{}"})
  void json_acceptsValidJson(String v) {
    assertThatCode(() -> FlagValueValidator.validate(FlagValueType.JSON, v))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(strings = {"{bad", "", "   ", "{\"a\":1} x", "{'a':1}", "{\"a\":1}{\"b\":2}"})
  void json_rejectsInvalidJson(String v) {
    assertThatThrownBy(() -> FlagValueValidator.validate(FlagValueType.JSON, v))
        .isInstanceOf(InvalidRequestException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"anything", "", "{bad", "123", "  spaced  "})
  void string_acceptsAnything(String v) {
    assertThatCode(() -> FlagValueValidator.validate(FlagValueType.STRING, v))
        .doesNotThrowAnyException();
  }

  @Test
  void errorMessageDoesNotEchoTheValue() {
    assertThatThrownBy(() -> FlagValueValidator.validate(FlagValueType.INTEGER, "s3cr3t-abc"))
        .hasMessageNotContaining("s3cr3t-abc");
  }

  // ---- QA edge cases (independent test pass, S-0.4) ----

  @ParameterizedTest
  @ValueSource(strings = {"+5", "-9223372036854775808", "007", "-0"})
  void integer_acceptsLongParseLongForms(String v) {
    // Spec says Long.parseLong semantics: leading '+', leading zeros are accepted.
    assertThatCode(() -> FlagValueValidator.validate(FlagValueType.INTEGER, v))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "5 ",
        " 5",
        "\t5",
        "5\n",
        "-9223372036854775809",
        "0x10",
        "1_000",
        "--5",
        "+",
        "-",
        "NaN"
      })
  void integer_rejectsWhitespaceOverflowAndNonDecimal(String v) {
    assertThatThrownBy(() -> FlagValueValidator.validate(FlagValueType.INTEGER, v))
        .isInstanceOf(InvalidRequestException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"TRUE", "FALSE", "true ", "false\n", "\ttrue", " ", "t", "0"})
  void boolean_isCaseAndWhitespaceStrict(String v) {
    assertThatThrownBy(() -> FlagValueValidator.validate(FlagValueType.BOOLEAN, v))
        .isInstanceOf(InvalidRequestException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"a\":1}\n{\"b\":2}",
        "1 2",
        "[1,2",
        "[1,2]]",
        "{\"a\":1,}",
        "undefined",
        "NaN",
        "{\"a\":1} //c",
        "\u0000",
        "tru"
      })
  void json_rejectsTrailingTokensAndMalformedScalars(String v) {
    assertThatThrownBy(() -> FlagValueValidator.validate(FlagValueType.JSON, v))
        .isInstanceOf(InvalidRequestException.class);
  }

  @Test
  void json_acceptsSurroundingWhitespace() {
    assertThatCode(() -> FlagValueValidator.validate(FlagValueType.JSON, " \n{\"a\": 1}\n "))
        .doesNotThrowAnyException();
  }

  @Test
  void veryLongInputs_doNotCrashAndAreHandledPerType() {
    String big = "9".repeat(200_000);
    assertThatThrownBy(() -> FlagValueValidator.validate(FlagValueType.INTEGER, big))
        .isInstanceOf(InvalidRequestException.class);
    assertThatThrownBy(() -> FlagValueValidator.validate(FlagValueType.BOOLEAN, big))
        .isInstanceOf(InvalidRequestException.class);
    assertThatCode(() -> FlagValueValidator.validate(FlagValueType.STRING, big))
        .doesNotThrowAnyException();
    // long but well-formed JSON string / array must be accepted
    assertThatCode(
            () ->
                FlagValueValidator.validate(FlagValueType.JSON, "\"" + "a".repeat(200_000) + "\""))
        .doesNotThrowAnyException();
  }

  @Test
  void json_deeplyNestedOrHugeNumber_isRejectedCleanly_notAnUncheckedCrash() {
    String deep = "[".repeat(100_000) + "]".repeat(100_000);
    assertThatCode(() -> FlagValueValidator.isValid(FlagValueType.JSON, deep))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> FlagValueValidator.validate(FlagValueType.JSON, deep))
        .isInstanceOf(InvalidRequestException.class);
    String hugeNum = "1" + "0".repeat(100_000);
    assertThatCode(() -> FlagValueValidator.isValid(FlagValueType.JSON, hugeNum))
        .doesNotThrowAnyException();
  }

  @Test
  void isValid_agreesWithValidate() {
    org.assertj.core.api.Assertions.assertThat(
            FlagValueValidator.isValid(FlagValueType.INTEGER, "x"))
        .isFalse();
    org.assertj.core.api.Assertions.assertThat(
            FlagValueValidator.isValid(FlagValueType.INTEGER, null))
        .isTrue();
    org.assertj.core.api.Assertions.assertThat(
            FlagValueValidator.isValid(FlagValueType.BOOLEAN, "true"))
        .isTrue();
  }

  // ---- S-0.5 (F19 length part, D-10): characters, not bytes ----

  @Test
  void validateLength_boundary() {
    assertThatCode(() -> FlagValueValidator.validateLength("a".repeat(8192), 8192))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> FlagValueValidator.validateLength("a".repeat(8193), 8192))
        .isInstanceOf(InvalidRequestException.class)
        .hasMessageContaining("8192")
        .hasMessageNotContaining("aaaa");
    assertThatCode(() -> FlagValueValidator.validateLength(null, 8192)).doesNotThrowAnyException();
  }

  @Test
  void length_countsCharactersNotBytes() {
    // 8192 two-byte characters = 16384 bytes, still accepted.
    String multiByte = "\u00e9".repeat(8192);
    assertThat(FlagValueValidator.isWithinLength(multiByte, 8192)).isTrue();
    assertThat(FlagValueValidator.isWithinLength(multiByte + "x", 8192)).isFalse();
  }

  @Test
  void length_utf16CodeUnits_surrogatePairCountsAsTwo() {
    // U+1F600 is one code point but two UTF-16 code units: 4096 of them = 8192 units (accepted),
    // 4097 = 8194 units (rejected). Documents the chars = String.length() semantics.
    String emoji = "\uD83D\uDE00";
    assertThat(FlagValueValidator.isWithinLength(emoji.repeat(4096), 8192)).isTrue();
    assertThat(FlagValueValidator.isWithinLength(emoji.repeat(4097), 8192)).isFalse();
  }

  @Test
  void length_emptyAndNullAccepted() {
    assertThat(FlagValueValidator.isWithinLength("", 8192)).isTrue();
    assertThat(FlagValueValidator.isWithinLength(null, 8192)).isTrue();
    assertThatCode(() -> FlagValueValidator.validateLength("", 8192)).doesNotThrowAnyException();
  }
}
