package org.aibles.feature_flag.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.domain.entity.EnvironmentApiKey;
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
import org.aibles.feature_flag.repository.EnvironmentApiKeyRepository;
import org.aibles.feature_flag.repository.EnvironmentRepository;
import org.aibles.feature_flag.repository.ProjectRepository;
import org.aibles.feature_flag.service.EnvironmentApiKeyService;
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

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 5, 12, 0);

  @Mock EnvironmentRepository environmentRepository;
  @Mock EnvironmentApiKeyRepository apiKeyRepository;
  @Mock ProjectRepository projectRepository;
  @Mock PermissionService permissionService;
  @Mock ApplicationEventPublisher eventPublisher;
  @Mock AuditService auditService;

  EnvironmentServiceImpl service;

  // The legacy env-level rotate endpoint delegates to the real key service (task 5), wired here
  // with the same mocked collaborators so side effects (revocation, the single event publish,
  // the single audit row) are observable end-to-end rather than swallowed by a mock.
  EnvironmentApiKeyService apiKeyService;

  UUID projectId = UUID.randomUUID();
  UUID envId = UUID.randomUUID();
  Project project;
  Environment env;

  @BeforeEach
  void setUp() {
    Clock clock =
        Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
    apiKeyService =
        new EnvironmentApiKeyServiceImpl(
            apiKeyRepository,
            environmentRepository,
            permissionService,
            eventPublisher,
            auditService,
            clock);
    service =
        new EnvironmentServiceImpl(
            environmentRepository,
            apiKeyRepository,
            projectRepository,
            permissionService,
            apiKeyService,
            auditService,
            clock);
    Organization org = Organization.builder().id(UUID.randomUUID()).name("org").build();
    project = Project.builder().id(projectId).organization(org).name("proj").build();
    env = Environment.builder().id(envId).project(project).name("prod").build();
    doNothing().when(permissionService).requireRoleForProject(any(), any(MemberRole[].class));
    doNothing().when(permissionService).requireRoleForEnvironment(any(), any(MemberRole[].class));
    // Needed so the real apiKeyService (wired above) can round-trip a saved key back to its
    // caller instead of getting null, the way JPA's save() behaves in practice.
    when(apiKeyRepository.save(any(EnvironmentApiKey.class))).thenAnswer(inv -> inv.getArgument(0));
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

    ArgumentCaptor<EnvironmentApiKey> saved = ArgumentCaptor.forClass(EnvironmentApiKey.class);
    verify(apiKeyRepository).save(saved.capture());
    assertThat(saved.getValue().getKeyHash())
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
  void legacyRotateRotatesTheSingleActiveKey() {
    when(environmentRepository.findById(envId)).thenReturn(Optional.of(env));
    EnvironmentApiKey theOnlyKey =
        EnvironmentApiKey.builder()
            .id(UUID.randomUUID())
            .environment(env)
            .name("default")
            .keyHash(ApiKeyHasher.hash("old-key"))
            .build();
    when(apiKeyRepository.findActiveByEnvironmentId(eq(envId), any()))
        .thenReturn(List.of(theOnlyKey));
    when(apiKeyRepository.findById(theOnlyKey.getId())).thenReturn(Optional.of(theOnlyKey));
    when(permissionService.currentUserEmail()).thenReturn("actor@example.com");

    EnvironmentSecretResponse response = service.rotateApiKey(envId);

    // The old key is now revoked (graceHours=0, today's hard-cutover behaviour), so it can no
    // longer authenticate.
    assertThat(theOnlyKey.getRevokedAt()).isEqualTo(NOW);
    assertThat(response.getApiKey()).isNotNull();

    // Rotation saves the fresh key first, then the updated old key — the fresh one is the
    // first save() call.
    ArgumentCaptor<EnvironmentApiKey> newKeyCaptor =
        ArgumentCaptor.forClass(EnvironmentApiKey.class);
    verify(apiKeyRepository, times(2)).save(newKeyCaptor.capture());
    assertThat(newKeyCaptor.getAllValues().get(0).getKeyHash())
        .isEqualTo(ApiKeyHasher.hash(response.getApiKey()))
        .isNotEqualTo(ApiKeyHasher.hash("old-key"));

    // apiKeyService.rotate() publishes the event and audits the rotation itself — the legacy
    // endpoint must not do so a second time.
    ArgumentCaptor<ApiKeyRotatedEvent> captor = ArgumentCaptor.forClass(ApiKeyRotatedEvent.class);
    verify(eventPublisher, times(1)).publishEvent(captor.capture());
    ApiKeyRotatedEvent event = captor.getValue();
    assertThat(event.environmentName()).isEqualTo("prod");
    assertThat(event.projectName()).isEqualTo("proj");
    assertThat(event.actorEmail()).isEqualTo("actor@example.com");

    // Security: the rotation is audited as an event only — the key must never reach the audit row
    // (before/after are both null).
    verify(auditService, times(1))
        .record(
            eq(AuditEntityType.API_KEY),
            any(),
            eq(AuditAction.ROTATE_API_KEY),
            any(),
            isNull(),
            isNull());
  }

  @Test
  void legacyRotateRefusesWhenTheEnvironmentHasSeveralActiveKeys() {
    when(environmentRepository.findById(envId)).thenReturn(Optional.of(env));
    EnvironmentApiKey keyA =
        EnvironmentApiKey.builder()
            .id(UUID.randomUUID())
            .environment(env)
            .name("a")
            .keyHash(ApiKeyHasher.hash("a"))
            .build();
    EnvironmentApiKey keyB =
        EnvironmentApiKey.builder()
            .id(UUID.randomUUID())
            .environment(env)
            .name("b")
            .keyHash(ApiKeyHasher.hash("b"))
            .build();
    when(apiKeyRepository.findActiveByEnvironmentId(eq(envId), any()))
        .thenReturn(List.of(keyA, keyB));

    assertThatThrownBy(() -> service.rotateApiKey(envId))
        .isInstanceOf(DuplicateResourceException.class)
        .hasMessageContaining("/api-keys/{keyId}/rotate");
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  void legacyRotateRefusesWhenTheEnvironmentHasNoActiveKey() {
    when(environmentRepository.findById(envId)).thenReturn(Optional.of(env));
    when(apiKeyRepository.findActiveByEnvironmentId(eq(envId), any())).thenReturn(List.of());

    assertThatThrownBy(() -> service.rotateApiKey(envId))
        .isInstanceOf(DuplicateResourceException.class);
    verify(eventPublisher, never()).publishEvent(any());
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

  /**
   * Both call sites must hand the PDP the environment itself, not just its project — the production
   * rules read attributes off it, and a project-scoped ResourceRef silently disables them.
   */
  @Test
  void rotate_passesTheEnvironmentToThePdpSoProductionRulesApply() {
    Environment prod = productionEnv();
    EnvironmentApiKey theOnlyKey =
        EnvironmentApiKey.builder()
            .id(UUID.randomUUID())
            .environment(prod)
            .name("default")
            .keyHash(ApiKeyHasher.hash("old-key"))
            .build();
    when(apiKeyRepository.findActiveByEnvironmentId(eq(envId), any()))
        .thenReturn(List.of(theOnlyKey));
    when(apiKeyRepository.findById(theOnlyKey.getId())).thenReturn(Optional.of(theOnlyKey));
    when(permissionService.currentUserEmail()).thenReturn("actor@example.com");

    service.rotateApiKey(envId);

    ArgumentCaptor<PermissionService.ResourceRef> captor =
        ArgumentCaptor.forClass(PermissionService.ResourceRef.class);
    verify(permissionService).check(eq(Action.ENV_ROTATE_KEY), captor.capture());
    assertThat(captor.getValue().environment()).isSameAs(prod);
  }

  @Test
  void delete_passesTheEnvironmentToThePdpSoProductionRulesApply() {
    Environment prod = productionEnv();

    service.delete(envId);

    ArgumentCaptor<PermissionService.ResourceRef> captor =
        ArgumentCaptor.forClass(PermissionService.ResourceRef.class);
    verify(permissionService).check(eq(Action.ENV_DELETE), captor.capture());
    assertThat(captor.getValue().environment()).isSameAs(prod);
  }
}
