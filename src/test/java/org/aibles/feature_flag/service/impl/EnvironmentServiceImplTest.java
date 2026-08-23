package org.aibles.feature_flag.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.domain.entity.Organization;
import org.aibles.feature_flag.domain.entity.Project;
import org.aibles.feature_flag.domain.enums.Action;
import org.aibles.feature_flag.domain.enums.AuditAction;
import org.aibles.feature_flag.domain.enums.AuditEntityType;
import org.aibles.feature_flag.domain.enums.EnvType;
import org.aibles.feature_flag.domain.enums.MemberRole;
import org.aibles.feature_flag.dto.request.CreateEnvironmentRequest;
import org.aibles.feature_flag.dto.request.UpdateEnvironmentRequest;
import org.aibles.feature_flag.dto.response.EnvironmentResponse;
import org.aibles.feature_flag.dto.response.EnvironmentSecretResponse;
import org.aibles.feature_flag.exception.DuplicateResourceException;
import org.aibles.feature_flag.exception.ResourceNotFoundException;
import org.aibles.feature_flag.notification.event.ApiKeyRotatedEvent;
import org.aibles.feature_flag.repository.EnvironmentRepository;
import org.aibles.feature_flag.repository.ProjectRepository;
import org.aibles.feature_flag.util.ApiKeyHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EnvironmentServiceImplTest {

  @Mock EnvironmentRepository environmentRepository;
  @Mock ProjectRepository projectRepository;
  @Mock PermissionService permissionService;
  @Mock ApplicationEventPublisher eventPublisher;
  @Mock AuditService auditService;

  EnvironmentServiceImpl service;

  UUID projectId = UUID.randomUUID();
  UUID envId = UUID.randomUUID();
  Project project;
  Environment env;

  @BeforeEach
  void setUp() {
    service =
        new EnvironmentServiceImpl(
            environmentRepository,
            projectRepository,
            permissionService,
            eventPublisher,
            auditService);
    Organization org = Organization.builder().id(UUID.randomUUID()).name("org").build();
    project = Project.builder().id(projectId).organization(org).name("proj").build();
    env =
        Environment.builder()
            .id(envId)
            .project(project)
            .name("prod")
            .apiKeyHash(ApiKeyHasher.hash("old-key"))
            .build();
    doNothing().when(permissionService).requireRoleForProject(any(), any(MemberRole[].class));
    doNothing().when(permissionService).requireRoleForEnvironment(any(), any(MemberRole[].class));
  }

  @Test
  void create_throwsDuplicate_whenNameExistsInProject() {
    when(environmentRepository.existsByProjectIdAndName(projectId, "prod")).thenReturn(true);

    CreateEnvironmentRequest req = new CreateEnvironmentRequest();
    req.setProjectId(projectId);
    req.setName("prod");

    assertThatThrownBy(() -> service.create(req)).isInstanceOf(DuplicateResourceException.class);
    verify(environmentRepository, never()).save(any());
  }

  @Test
  void create_storesHashAndReturnsPlaintextOnce() {
    when(environmentRepository.existsByProjectIdAndName(projectId, "staging")).thenReturn(false);
    when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
    when(environmentRepository.save(any(Environment.class))).thenAnswer(inv -> inv.getArgument(0));

    CreateEnvironmentRequest req = new CreateEnvironmentRequest();
    req.setProjectId(projectId);
    req.setName("staging");

    EnvironmentSecretResponse response = service.create(req);

    assertThat(response.getApiKey()).matches("[0-9a-f]{64}");

    ArgumentCaptor<Environment> saved = ArgumentCaptor.forClass(Environment.class);
    verify(environmentRepository).save(saved.capture());
    assertThat(saved.getValue().getApiKeyHash())
        .isEqualTo(ApiKeyHasher.hash(response.getApiKey()))
        .isNotEqualTo(response.getApiKey());
  }

  @Test
  void create_throwsResourceNotFound_whenProjectDoesNotExist() {
    when(environmentRepository.existsByProjectIdAndName(projectId, "x")).thenReturn(false);
    when(projectRepository.findById(projectId)).thenReturn(Optional.empty());

    CreateEnvironmentRequest req = new CreateEnvironmentRequest();
    req.setProjectId(projectId);
    req.setName("x");

    assertThatThrownBy(() -> service.create(req)).isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  void rotate_replacesHashAndReturnsNewPlaintextOnce() {
    when(environmentRepository.findById(envId)).thenReturn(Optional.of(env));
    when(environmentRepository.save(any(Environment.class))).thenAnswer(inv -> inv.getArgument(0));

    when(permissionService.currentUserEmail()).thenReturn("actor@example.com");

    EnvironmentSecretResponse response = service.rotateApiKey(envId);

    assertThat(response.getApiKey()).matches("[0-9a-f]{64}");
    assertThat(env.getApiKeyHash())
        .isEqualTo(ApiKeyHasher.hash(response.getApiKey()))
        .isNotEqualTo(ApiKeyHasher.hash("old-key"));

    ArgumentCaptor<ApiKeyRotatedEvent> captor = ArgumentCaptor.forClass(ApiKeyRotatedEvent.class);
    verify(eventPublisher).publishEvent(captor.capture());
    ApiKeyRotatedEvent event = captor.getValue();
    assertThat(event.environmentName()).isEqualTo("prod");
    assertThat(event.projectName()).isEqualTo("proj");
    assertThat(event.actorEmail()).isEqualTo("actor@example.com");

    // Security: the rotation is audited as an event only — the key must never reach the audit row
    // (before/after are both null).
    verify(auditService)
        .record(
            eq(AuditEntityType.API_KEY),
            eq(envId),
            eq(AuditAction.ROTATE_API_KEY),
            any(),
            isNull(),
            isNull());
  }

  @Test
  void update_changesNameAndDescription() {
    when(environmentRepository.findById(envId)).thenReturn(Optional.of(env));
    when(environmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    UpdateEnvironmentRequest req = new UpdateEnvironmentRequest();
    req.setName("production");
    req.setDescription("Main env");

    EnvironmentResponse response = service.update(envId, req);

    assertThat(response.getName()).isEqualTo("production");
    assertThat(response.getDescription()).isEqualTo("Main env");
  }

  @Test
  void listByProject_delegatesToRepository() {
    when(environmentRepository.findAllByProjectId(eq(projectId), any()))
        .thenReturn(new PageImpl<>(List.of(env)));

    Page<EnvironmentResponse> result = service.listByProject(projectId, PageRequest.of(0, 20));

    assertThat(result.getContent()).hasSize(1);
    assertThat(result.getContent().get(0).getName()).isEqualTo("prod");
  }

  @Test
  void delete_deletesById() {
    when(environmentRepository.findById(envId)).thenReturn(Optional.of(env));

    service.delete(envId);

    verify(environmentRepository).deleteById(envId);
  }

  // --- ABAC: protection attributes are OWNER-only (rules B/D cannot be stripped by an ADMIN) ---

  private Environment productionEnv() {
    Environment prod =
        Environment.builder()
            .id(envId)
            .project(project)
            .name("prod")
            .type(EnvType.PRODUCTION)
            .apiKeyHash(ApiKeyHasher.hash("old-key"))
            .build();
    when(environmentRepository.findById(envId)).thenReturn(Optional.of(prod));
    return prod;
  }

  @Test
  void update_downgradingProductionType_requiresManageProtection() {
    productionEnv();
    doThrow(new org.aibles.feature_flag.exception.UnauthorizedException("nope"))
        .when(permissionService)
        .check(eq(Action.ENV_MANAGE_PROTECTION), any());

    UpdateEnvironmentRequest req = new UpdateEnvironmentRequest();
    req.setType(EnvType.DEVELOPMENT);

    assertThatThrownBy(() -> service.update(envId, req))
        .isInstanceOf(org.aibles.feature_flag.exception.UnauthorizedException.class);
    verify(environmentRepository, never()).save(any());
  }

  @Test
  void update_editingChangeWindow_requiresManageProtection() {
    productionEnv();
    doThrow(new org.aibles.feature_flag.exception.UnauthorizedException("nope"))
        .when(permissionService)
        .check(eq(Action.ENV_MANAGE_PROTECTION), any());

    UpdateEnvironmentRequest req = new UpdateEnvironmentRequest();
    req.setChangeWindowStartHour(9);
    req.setChangeWindowEndHour(17);

    assertThatThrownBy(() -> service.update(envId, req))
        .isInstanceOf(org.aibles.feature_flag.exception.UnauthorizedException.class);
    verify(environmentRepository, never()).save(any());
  }

  @Test
  void update_nonProtectionChange_doesNotRequireManageProtection() {
    productionEnv();
    when(environmentRepository.save(any(Environment.class))).thenAnswer(inv -> inv.getArgument(0));

    UpdateEnvironmentRequest req = new UpdateEnvironmentRequest();
    req.setDescription("just a description");

    service.update(envId, req);

    verify(permissionService, never()).check(eq(Action.ENV_MANAGE_PROTECTION), any());
  }
}
