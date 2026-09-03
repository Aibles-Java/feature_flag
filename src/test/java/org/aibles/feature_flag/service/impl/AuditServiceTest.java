package org.aibles.feature_flag.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.aibles.feature_flag.domain.entity.AuditLog;
import org.aibles.feature_flag.domain.enums.Action;
import org.aibles.feature_flag.domain.enums.AuditAction;
import org.aibles.feature_flag.domain.enums.AuditEntityType;
import org.aibles.feature_flag.dto.response.AuditLogResponse;
import org.aibles.feature_flag.dto.response.ProjectResponse;
import org.aibles.feature_flag.exception.UnauthorizedException;
import org.aibles.feature_flag.repository.AuditLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

  @Mock AuditLogRepository auditLogRepository;
  @Mock PermissionService permissionService;

  AuditService service;

  @org.junit.jupiter.api.BeforeEach
  void setUp() {
    service = new AuditService(auditLogRepository, permissionService);
  }

  @Test
  void record_savesOneRowWithActorOrgAndSerializedStates() {
    UUID actor = UUID.randomUUID();
    UUID orgId = UUID.randomUUID();
    UUID entityId = UUID.randomUUID();
    when(permissionService.currentUserId()).thenReturn(actor);
    ProjectResponse after =
        ProjectResponse.builder()
            .id(entityId)
            .name("Backend")
            .organisationId(orgId)
            .createdAt(LocalDateTime.of(2026, 7, 16, 10, 0))
            .build();

    service.record(AuditEntityType.PROJECT, entityId, AuditAction.CREATE, orgId, null, after);

    ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
    verify(auditLogRepository).save(captor.capture());
    AuditLog saved = captor.getValue();
    assertThat(saved.getActorUserId()).isEqualTo(actor);
    assertThat(saved.getOrgId()).isEqualTo(orgId);
    assertThat(saved.getAction()).isEqualTo(AuditAction.CREATE);
    assertThat(saved.getEntityType()).isEqualTo(AuditEntityType.PROJECT);
    assertThat(saved.getEntityId()).isEqualTo(entityId);
    assertThat(saved.getBeforeState()).isNull();
    // DTO serialized to a map; LocalDateTime rendered as an ISO string (JSR-310, not a timestamp).
    assertThat(saved.getAfterState())
        .containsEntry("name", "Backend")
        .containsEntry("createdAt", "2026-07-16T10:00:00");
  }

  @Test
  void list_requiresViewerRoleAndMapsRows() {
    UUID orgId = UUID.randomUUID();
    AuditLog row =
        AuditLog.builder()
            .id(UUID.randomUUID())
            .orgId(orgId)
            .action(AuditAction.DELETE)
            .entityType(AuditEntityType.FEATURE_FLAG)
            .build();
    when(auditLogRepository.findByOrgId(eq(orgId), any())).thenReturn(new PageImpl<>(List.of(row)));

    var result = service.list(orgId, PageRequest.of(0, 20));

    verify(permissionService).check(Action.AUDIT_READ, PermissionService.ResourceRef.org(orgId));
    assertThat(result.getContent()).hasSize(1);
    AuditLogResponse mapped = result.getContent().get(0);
    assertThat(mapped.getAction()).isEqualTo(AuditAction.DELETE);
    assertThat(mapped.getEntityType()).isEqualTo(AuditEntityType.FEATURE_FLAG);
  }

  @Test
  void list_propagatesUnauthorized_whenCallerLacksRole() {
    UUID orgId = UUID.randomUUID();
    doThrow(new UnauthorizedException("nope"))
        .when(permissionService)
        .check(eq(Action.AUDIT_READ), any());

    assertThatThrownBy(() -> service.list(orgId, PageRequest.of(0, 20)))
        .isInstanceOf(UnauthorizedException.class);
  }
}
