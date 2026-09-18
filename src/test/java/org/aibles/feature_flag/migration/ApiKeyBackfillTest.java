package org.aibles.feature_flag.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import org.aibles.feature_flag.domain.entity.EnvironmentApiKey;
import org.aibles.feature_flag.repository.EnvironmentApiKeyRepository;
import org.aibles.feature_flag.util.ApiKeyHasher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * The highest-consequence assertion in this feature: a key issued before migration 020 must keep
 * authenticating after it. The migration copies the stored hash rather than recomputing it, and
 * this test is what proves the copy landed where the authentication lookup now reads.
 *
 * <p>The pre-migration environment is seeded by changeset {@code 020-0}, which lives ONLY in {@code
 * src/test/resources/db/changelog/020-0-seed-backfill-fixture.xml} and is reachable only through
 * {@code db.changelog-backfill-test.xml} (set as {@code spring.liquibase.change-log} below) — never
 * through {@code db.changelog-master.xml}, which every real deploy and every other test uses. An
 * earlier version of this fixture instead lived inside the production migration file, gated by a
 * Liquibase {@code context="backfill-test"} attribute, on the mistaken belief that a context-tagged
 * changeset is skipped unless that context is explicitly activated. Liquibase does the opposite: a
 * changeset's context matches whenever the *runtime* context set is empty, which it always was here
 * ({@code spring.liquibase.contexts} is never set anywhere in this repo) — so that fixture, a live
 * publicly-known SDK credential for a fake PRODUCTION environment, was seeded into every real
 * database this migration ever ran against. Physical file separation (this file simply isn't on the
 * path {@code db.changelog-master.xml} walks), not a context tag, is what keeps it out of
 * production now.
 *
 * <p>Runs against its own H2 database rather than the shared {@code testdb} used by plain
 * {@code @SpringBootTest} classes (see {@code RateLimitIntegrationTest}'s note on the same quirk).
 * Confirmed empirically, not merely by suspicion: pointing this test at the shared {@code testdb}
 * (removing the datasource override, keeping everything else identical) reproduces a full-suite
 * failure reliably — {@code SpringLiquibase.afterPropertiesSet()} throws {@code
 * liquibase.exception.DatabaseException: Table "databasechangelog" already exists} while this
 * test's context is bootstrapping. {@code db.changelog-master.xml} and {@code
 * db.changelog-backfill-test.xml} are two independent top-level changelogs that both include the
 * same underlying "core" (001-019) and "020" changeset files; when a plain {@code @SpringBootTest}
 * using the master changelog and this test's context (using the backfill changelog) both target the
 * same physical, persistent ({@code DB_CLOSE_DELAY=-1}) named H2 database, each gets its own
 * independently-bootstrapped {@code SpringLiquibase} bean doing its own check-then-create of the
 * {@code DATABASECHANGELOG} bookkeeping table against that shared database — which is exactly what
 * raced here. A dedicated database keeps this test's Liquibase bootstrap fully isolated from any
 * other context's, eliminating that hazard by construction. (An earlier version of this comment
 * guessed a different, unverified mechanism — 020-2 dropping {@code environments.api_key_hash} out
 * from under a late-running seed changeset. That was never actually observed; the real failure,
 * captured above, is the changelog-table race.)
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:apikeybackfill-testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
      "spring.liquibase.change-log=classpath:db/changelog/db.changelog-backfill-test.xml"
    })
@ActiveProfiles("test")
class ApiKeyBackfillTest {

  private static final UUID SEEDED_ENVIRONMENT_ID =
      UUID.fromString("00000000-0000-0000-0000-0000000000c1");
  private static final String SEEDED_PLAINTEXT = "legacy-plaintext-key";

  @Autowired private EnvironmentApiKeyRepository repository;
  @Autowired private Clock clock;

  @Test
  void aKeyIssuedBeforeTheMigrationStillResolves() {
    EnvironmentApiKey key =
        repository.findByKeyHash(ApiKeyHasher.hash(SEEDED_PLAINTEXT)).orElseThrow();

    assertThat(key.getEnvironment().getId()).isEqualTo(SEEDED_ENVIRONMENT_ID);
    assertThat(key.getName()).isEqualTo("default");
    assertThat(key.getRevokedAt()).isNull();
    assertThat(key.getExpiresAt()).isNull();
  }

  @Test
  void theBackfilledKeyIsActive() {
    EnvironmentApiKey key =
        repository.findByKeyHash(ApiKeyHasher.hash(SEEDED_PLAINTEXT)).orElseThrow();

    assertThat(repository.findActiveByKeyHash(key.getKeyHash(), LocalDateTime.now(clock)))
        .isPresent();
  }

  @Test
  void theBackfilledKeyHasNoRecoverablePrefix() {
    EnvironmentApiKey key =
        repository.findByKeyHash(ApiKeyHasher.hash(SEEDED_PLAINTEXT)).orElseThrow();

    // The plaintext was never stored, so no prefix can be invented for a migrated row.
    assertThat(key.getKeyPrefix()).isEmpty();
  }
}
