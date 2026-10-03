package org.aibles.feature_flag.migration;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Pins the security property Critical 1 protects: {@code db.changelog-master.xml} — the changelog
 * every real deploy and every other {@code @SpringBootTest} uses — must never seed the {@code
 * legacy-prod} backfill fixture (org "Backfill Fixture Org", environment "legacy-prod"
 * authenticating with the publicly-known plaintext {@code legacy-plaintext-key}). That fixture is
 * meant to exist only in {@code db.changelog-backfill-test.xml} (see {@link ApiKeyBackfillTest}), a
 * changelog {@code db.changelog-master.xml} never includes.
 *
 * <p>Deliberately uses the plain, default {@code @SpringBootTest} configuration — the same
 * changelog and datasource resolution any other un-annotated {@code @SpringBootTest} in this suite
 * gets — so this test fails the same way a real deploy would break if the fixture were ever
 * reintroduced into a file {@code db.changelog-master.xml} includes.
 */
@SpringBootTest
@ActiveProfiles("test")
class NoBackfillFixtureInProductionChangelogTest {

  @PersistenceContext private EntityManager em;

  @Test
  void theProductionChangelogNeverSeedsTheBackfillFixtureEnvironment() {
    Long count =
        em.createQuery("SELECT COUNT(e) FROM Environment e WHERE e.name = :name", Long.class)
            .setParameter("name", "legacy-prod")
            .getSingleResult();

    assertThat(count)
        .as(
            "db.changelog-master.xml must never seed the 'legacy-prod' backfill fixture "
                + "(a live, publicly-known SDK credential) — that fixture belongs only in "
                + "db.changelog-backfill-test.xml")
        .isZero();
  }
}
