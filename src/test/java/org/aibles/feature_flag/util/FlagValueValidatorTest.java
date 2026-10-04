package org.aibles.feature_flag.util;

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
}
