package org.aibles.feature_flag.apikey;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.domain.entity.EnvironmentApiKey;
import org.aibles.feature_flag.domain.entity.Organization;
import org.aibles.feature_flag.domain.entity.Project;
import org.aibles.feature_flag.notification.event.ApiKeyExpiringEvent;
import org.aibles.feature_flag.repository.EnvironmentApiKeyRepository;
import org.aibles.feature_flag.repository.EnvironmentRepository;
import org.aibles.feature_flag.repository.OrganizationRepository;
import org.aibles.feature_flag.repository.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Pins the two traps documented on {@link ApiKeyExpiryNotifier}. If the event were published
 * outside a transaction — either directly from the scheduler, or through a self-invoked
 * {@code @Transactional} method — the AFTER_COMMIT listener below would never run and this test
 * fails. Not {@code @Transactional} itself: the notifier's {@code REQUIRES_NEW} must really commit.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@TestPropertySource(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:testdb_keyexpiry;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=KEY,VALUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
    })
@Import(ApiKeyExpiryWarningIntegrationTest.RecordingListener.class)
class ApiKeyExpiryWarningIntegrationTest {

  /** Same phase and no fallbackExecution — exactly like the real Slack and webhook listeners. */
  public static class RecordingListener {
    final List<ApiKeyExpiringEvent> received = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(ApiKeyExpiringEvent event) {
      received.add(event);
    }
  }

  @Autowired ApiKeyExpiryScheduler scheduler;
  @Autowired RecordingListener listener;
  @Autowired EnvironmentApiKeyRepository apiKeyRepository;
  @Autowired EnvironmentRepository environmentRepository;
  @Autowired ProjectRepository projectRepository;
  @Autowired OrganizationRepository organizationRepository;

  @Test
  void aScanDeliversOneWarningAfterCommitAndARepeatScanDeliversNone() {
    Organization org =
        organizationRepository.save(
            Organization.builder().name("Acme").slug("acme-" + System.nanoTime()).build());
    Project project =
        projectRepository.save(Project.builder().organization(org).name("checkout").build());
    Environment env =
        environmentRepository.save(
            Environment.builder().project(project).name("production-" + System.nanoTime()).build());
    EnvironmentApiKey key =
        apiKeyRepository.saveAndFlush(
            EnvironmentApiKey.builder()
                .environment(env)
                .name("nightly-batch")
                .keyHash("expiry-it-" + System.nanoTime())
                .keyPrefix("e1a2b3c4")
                .expiresAt(LocalDateTime.now().plusDays(5))
                .build());

    scheduler.scan();

    assertThat(listener.received)
        .filteredOn(event -> event.keyId().equals(key.getId()))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.daysLeft()).isEqualTo(5);
              assertThat(event.projectName()).isEqualTo("checkout");
            });

    scheduler.scan();

    assertThat(listener.received).filteredOn(event -> event.keyId().equals(key.getId())).hasSize(1);
  }
}
