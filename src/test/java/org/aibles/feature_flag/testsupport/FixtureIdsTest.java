package org.aibles.feature_flag.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** S-0.0 AC1 (fixed UUID constants): guards against a random id slipping into the fixture. */
class FixtureIdsTest {

  @Test
  @DisplayName("AC1: every id constant is a fixed literal UUID, unique, and stable")
  void idsAreFixedLiterals() throws IllegalAccessException {
    List<UUID> ids = new ArrayList<>();
    for (Field f : FixtureIds.class.getDeclaredFields()) {
      if (Modifier.isStatic(f.getModifiers()) && f.getType() == UUID.class) {
        UUID v = (UUID) f.get(null);
        assertThat(v.toString()).as(f.getName()).startsWith("00000000-0000-4000-8000-");
        ids.add(v);
      }
    }
    assertThat(ids).hasSizeGreaterThanOrEqualTo(27).doesNotHaveDuplicates();
    assertThat(FixtureIds.FLAG_A1).hasToString("00000000-0000-4000-8000-000500000001");
    assertThat(FixtureIds.stateId(FixtureIds.FLAG_A1, FixtureIds.ENV_A_DEV))
        .isEqualTo(FixtureIds.stateId(FixtureIds.FLAG_A1, FixtureIds.ENV_A_DEV));
  }
}
