package org.aibles.feature_flag.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * S-2.1 AC1: migration 019 on a database that already holds flag state rows. A row seeded before
 * 019 must read version 0, and the column must be BIGINT NOT NULL DEFAULT 0. Runs on H2 (PostgreSQL
 * mode) in its own database; real PostgreSQL is a separate pre-G2 check (see backlog S-2.1 note).
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:flagstateversion-testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
      "spring.liquibase.change-log=classpath:db/changelog/db.changelog-019-existing-rows-test.xml"
    })
@ActiveProfiles("test")
class FlagStateVersionMigrationTest {

  @Autowired private JdbcTemplate jdbc;

  @Test
  void rowExistingBefore019ReadsVersionZero() {
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT version, enabled FROM flag_environment_states WHERE id = ?",
            java.util.UUID.fromString("00000000-0000-0000-0000-0000000000e2"));
    assertThat(((Number) row.get("version")).longValue()).isZero();
    assertThat(row.get("enabled")).isEqualTo(true);
  }

  @Test
  void columnIsBigintNotNullDefaultZero() {
    Map<String, Object> col =
        jdbc.queryForMap(
            "SELECT data_type, is_nullable, column_default FROM information_schema.columns "
                + "WHERE table_name = 'flag_environment_states' AND column_name = 'version'");
    assertThat(col.get("data_type").toString()).isEqualToIgnoringCase("BIGINT");
    assertThat(col.get("is_nullable")).isEqualTo("NO");
    assertThat(col.get("column_default").toString()).isEqualTo("0");
  }
}
