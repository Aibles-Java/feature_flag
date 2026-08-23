package org.aibles.feature_flag.repository;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.aibles.feature_flag.domain.entity.AuditLog;
import org.aibles.feature_flag.domain.enums.AuditAction;
import org.aibles.feature_flag.domain.enums.AuditEntityType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * Validates the audit_log JSON columns round-trip on H2 (issue #31 AC). This is the highest-risk
 * part of the design: the {@code before_state}/{@code after_state} maps are stored via
 * {@code @JdbcTypeCode(SqlTypes.JSON)} (jsonb on PostgreSQL, H2's {@code json} type in tests), and
 * must serialize and deserialize back to a usable {@code Map} on the H2 test path.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@TestPropertySource(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:testdb_audit;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=KEY,VALUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
    })
@Transactional
class AuditLogRepositoryTest {

  @PersistenceContext EntityManager em;
  @Autowired AuditLogRepository auditLogRepository;

  @Test
  void jsonStateColumnsRoundTripOnH2() {
    UUID orgId = UUID.randomUUID();
    UUID entityId = UUID.randomUUID();
    Map<String, Object> before = new LinkedHashMap<>();
    before.put("name", "Old Name");
    before.put("enabled", false);
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("name", "New Name");
    after.put("enabled", true);

    AuditLog saved =
        auditLogRepository.save(
            AuditLog.builder()
                .actorUserId(UUID.randomUUID())
                .orgId(orgId)
                .action(AuditAction.UPDATE)
                .entityType(AuditEntityType.FEATURE_FLAG)
                .entityId(entityId)
                .beforeState(before)
                .afterState(after)
                .build());
    em.flush();
    em.clear(); // force a real read from the DB, not the persistence-context cache

    AuditLog reloaded = auditLogRepository.findById(saved.getId()).orElseThrow();
    assertThat(reloaded.getAction()).isEqualTo(AuditAction.UPDATE);
    assertThat(reloaded.getEntityType()).isEqualTo(AuditEntityType.FEATURE_FLAG);
    assertThat(reloaded.getBeforeState())
        .containsEntry("name", "Old Name")
        .containsEntry("enabled", false);
    assertThat(reloaded.getAfterState())
        .containsEntry("name", "New Name")
        .containsEntry("enabled", true);
    assertThat(reloaded.getCreatedAt()).isNotNull();
  }

  @Test
  void findByOrgId_paginatesAndScopesToOrg() {
    UUID orgA = UUID.randomUUID();
    UUID orgB = UUID.randomUUID();
    for (int i = 0; i < 3; i++) {
      auditLogRepository.save(
          AuditLog.builder()
              .orgId(orgA)
              .action(AuditAction.CREATE)
              .entityType(AuditEntityType.PROJECT)
              .entityId(UUID.randomUUID())
              .build());
    }
    auditLogRepository.save(
        AuditLog.builder()
            .orgId(orgB)
            .action(AuditAction.CREATE)
            .entityType(AuditEntityType.PROJECT)
            .entityId(UUID.randomUUID())
            .build());
    em.flush();

    var page =
        auditLogRepository.findByOrgId(
            orgA, PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "createdAt")));

    assertThat(page.getTotalElements()).isEqualTo(3);
    assertThat(page.getContent()).hasSize(2);
    assertThat(page.getContent()).allSatisfy(a -> assertThat(a.getOrgId()).isEqualTo(orgA));
  }
}
