package org.aibles.feature_flag.migration;

import static org.assertj.core.api.Assertions.assertThat;

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
 * <p>The pre-migration environment is seeded by changeset {@code 020-0}, activated only by the
 * {@code backfill-test} Liquibase context set below.
 *
 * <p>Runs against its own H2 database rather than the shared {@code testdb} used by plain
 * {@code @SpringBootTest} classes (see {@code RateLimitIntegrationTest}'s note on the same quirk):
 * if a context without {@code backfill-test} active ran Liquibase against {@code testdb} first,
 * 020-2 would drop {@code environments.api_key_hash} before 020-0 ever got a chance to run under
 * this context, permanently breaking the seed insert for the rest of the JVM.
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:apikeybackfill-testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
      "spring.liquibase.contexts=backfill-test"
    })
@ActiveProfiles("test")
class ApiKeyBackfillTest {

  private static final UUID SEEDED_ENVIRONMENT_ID =
      UUID.fromString("00000000-0000-0000-0000-0000000000c1");
  private static final String SEEDED_PLAINTEXT = "legacy-plaintext-key";

  @Autowired private EnvironmentApiKeyRepository repository;

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

    assertThat(repository.findActiveByKeyHash(key.getKeyHash(), java.time.LocalDateTime.now()))
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
