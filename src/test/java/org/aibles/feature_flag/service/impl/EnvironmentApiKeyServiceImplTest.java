package org.aibles.feature_flag.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.aibles.feature_flag.config.ApiKeyProperties;
import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.domain.entity.EnvironmentApiKey;
import org.aibles.feature_flag.domain.entity.Organization;
import org.aibles.feature_flag.domain.entity.Project;
import org.aibles.feature_flag.domain.enums.Action;
import org.aibles.feature_flag.domain.enums.AuditAction;
import org.aibles.feature_flag.domain.enums.AuditEntityType;
import org.aibles.feature_flag.dto.request.CreateApiKeyRequest;
import org.aibles.feature_flag.dto.request.RotateApiKeyRequest;
import org.aibles.feature_flag.dto.response.ApiKeyResponse;
import org.aibles.feature_flag.dto.response.ApiKeySecretResponse;
import org.aibles.feature_flag.exception.DuplicateResourceException;
import org.aibles.feature_flag.exception.ResourceNotFoundException;
import org.aibles.feature_flag.exception.UnauthorizedException;
import org.aibles.feature_flag.repository.EnvironmentApiKeyRepository;
import org.aibles.feature_flag.repository.EnvironmentRepository;
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
class EnvironmentApiKeyServiceImplTest {

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 5, 12, 0);
  private static final UUID ORG_ID = UUID.randomUUID();
  private static final UUID PROJECT_ID = UUID.randomUUID();
  private static final UUID ENV_ID = UUID.randomUUID();
  private static final UUID KEY_ID = UUID.randomUUID();
  private static final UUID CREATED_BY = UUID.randomUUID();
  private static final String ACTIVE_KEY_HASH = ApiKeyHasher.hash("some-existing-plaintext-key");

  @Mock EnvironmentApiKeyRepository apiKeyRepository;
  @Mock EnvironmentRepository environmentRepository;
  @Mock PermissionService permissionService;
  @Mock ApplicationEventPublisher eventPublisher;
  @Mock AuditService auditService;

  EnvironmentApiKeyServiceImpl service;

  Organization organization;
  Project project;
  Environment environment;
  Clock fixedClock;

  @BeforeEach
  void setUp() {
    fixedClock =
        Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
    service =
        new EnvironmentApiKeyServiceImpl(
            apiKeyRepository,
            environmentRepository,
            permissionService,
            eventPublisher,
            auditService,
            fixedClock,
            new ApiKeyProperties(Duration.ofDays(90), null));

    organization = Organization.builder().id(ORG_ID).name("org").build();
    project = Project.builder().id(PROJECT_ID).organization(organization).name("proj").build();
    environment = Environment.builder().id(ENV_ID).project(project).name("prod").build();

    when(environmentRepository.findById(ENV_ID)).thenReturn(Optional.of(environment));
    when(apiKeyRepository.save(any(EnvironmentApiKey.class))).thenAnswer(inv -> inv.getArgument(0));
    when(apiKeyRepository.countActiveByEnvironmentId(eq(ENV_ID), any())).thenReturn(0L);
    when(permissionService.currentUserId()).thenReturn(CREATED_BY);
  }

  private CreateApiKeyRequest request(String name, LocalDateTime expiresAt) {
    CreateApiKeyRequest request = new CreateApiKeyRequest();
    request.setName(name);
    request.setExpiresAt(expiresAt);
    return request;
  }

  private EnvironmentApiKey activeKey() {
    return EnvironmentApiKey.builder()
        .id(KEY_ID)
        .environment(environment)
        .name("ios")
        .keyHash(ACTIVE_KEY_HASH)
        .keyPrefix("abcd1234")
        .createdBy(CREATED_BY)
        .createdAt(NOW.minusDays(1))
        .build();
  }

  private EnvironmentApiKey keyExpiringAt(LocalDateTime expiresAt) {
    EnvironmentApiKey key = activeKey();
    key.setExpiresAt(expiresAt);
    return key;
  }

  private Environment otherEnvironment() {
    return Environment.builder().id(UUID.randomUUID()).project(project).name("other").build();
  }

  private EnvironmentApiKey captureSavedKey() {
    ArgumentCaptor<EnvironmentApiKey> captor = ArgumentCaptor.forClass(EnvironmentApiKey.class);
    verify(apiKeyRepository).save(captor.capture());
    return captor.getValue();
  }

  @Test
  void createReturnsThePlaintextOnceAndStoresOnlyItsHash() {
    ApiKeySecretResponse response = service.create(ENV_ID, request("ios", null));

    assertThat(response.getApiKey()).hasSize(64);
    EnvironmentApiKey saved = captureSavedKey();
    assertThat(saved.getKeyHash()).isEqualTo(ApiKeyHasher.hash(response.getApiKey()));
    assertThat(saved.getKeyHash()).isNotEqualTo(response.getApiKey());
    assertThat(saved.getKeyPrefix()).isEqualTo(response.getApiKey().substring(0, 8));
  }

  @Test
  void createChecksEnvKeyCreateAgainstTheTargetEnvironment() {
    service.create(ENV_ID, request("ios", null));

    // The environment must be attached to the ResourceRef, or the production rules never fire.
    verify(permissionService)
        .check(eq(Action.ENV_KEY_CREATE), argThat(ref -> ref.environment() == environment));
  }

  @Test
  void createAuditsTheEventWithoutTheKeyMaterial() {
    service.create(ENV_ID, request("ios", null));

    verify(auditService)
        .record(
            eq(AuditEntityType.API_KEY),
            any(),
            eq(AuditAction.CREATE_API_KEY),
            eq(ORG_ID),
            isNull(),
            isNull());
  }

  @Test
  void createRejectsAnEleventhActiveKey() {
    // Pinned to NOW, not any(): the cap check must read the injected Clock, not wall-clock time.
    when(apiKeyRepository.countActiveByEnvironmentId(eq(ENV_ID), eq(NOW))).thenReturn(10L);

    assertThatThrownBy(() -> service.create(ENV_ID, request("ios", null)))
        .isInstanceOf(DuplicateResourceException.class)
        .hasMessageContaining("maximum of 10 active API keys");
  }

  @Test
  void createSucceedsWhenBelowTheCap() {
    when(apiKeyRepository.countActiveByEnvironmentId(eq(ENV_ID), eq(NOW))).thenReturn(9L);

    assertThatCode(() -> service.create(ENV_ID, request("ios", null))).doesNotThrowAnyException();
  }

  @Test
  void createWithoutAnExpiryAppliesTheDefaultLifetime() {
    service.create(ENV_ID, request("ios", null));

    assertThat(captureSavedKey().getExpiresAt()).isEqualTo(NOW.plusDays(90));
  }

  @Test
  void createWithNeverExpiresStoresNoExpiry() {
    CreateApiKeyRequest request = request("ios-app", null);
    request.setNeverExpires(true);

    service.create(ENV_ID, request);

    assertThat(captureSavedKey().getExpiresAt()).isNull();
  }

  @Test
  void createWithAnExplicitExpiryStoresItUnchanged() {
    service.create(ENV_ID, request("contractor", NOW.plusDays(14)));

    assertThat(captureSavedKey().getExpiresAt()).isEqualTo(NOW.plusDays(14));
  }

  @Test
  void listNeverExposesThePlaintextOrTheHash() {
    when(apiKeyRepository.findAllByEnvironmentId(eq(ENV_ID), any()))
        .thenReturn(new PageImpl<>(List.of(activeKey())));

    Page<ApiKeyResponse> page = service.list(ENV_ID, PageRequest.of(0, 20));

    assertThat(page.getContent())
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.getKeyPrefix()).isEqualTo("abcd1234");
              assertThat(r)
                  .hasNoNullFieldsOrPropertiesExcept(
                      "expiresAt", "revokedAt", "rotatedAt", "lastUsedAt");
            });
    assertThat(page.getContent().toString()).doesNotContain(ACTIVE_KEY_HASH);
  }

  @Test
  void listChecksEnvReadAgainstTheProject() {
    when(apiKeyRepository.findAllByEnvironmentId(eq(ENV_ID), any()))
        .thenReturn(new PageImpl<>(List.of()));

    service.list(ENV_ID, PageRequest.of(0, 20));

    // Listing does not reach production behaviour, so — unlike create/revoke — the ResourceRef
    // is project-scoped only; it must not carry the environment.
    verify(permissionService)
        .check(
            eq(Action.ENV_READ),
            argThat(ref -> ref.projectId().equals(PROJECT_ID) && ref.environment() == null));
  }

  @Test
  void listMarksAnExpiredKeyInactive() {
    when(apiKeyRepository.findAllByEnvironmentId(eq(ENV_ID), any()))
        .thenReturn(new PageImpl<>(List.of(keyExpiringAt(NOW.minusDays(1)))));

    assertThat(service.list(ENV_ID, PageRequest.of(0, 20)).getContent())
        .singleElement()
        .extracting(ApiKeyResponse::isActive)
        .isEqualTo(false);
  }

  @Test
  void revokeStampsRevokedAtFromTheClock() {
    EnvironmentApiKey key = activeKey();
    when(apiKeyRepository.findById(KEY_ID)).thenReturn(Optional.of(key));

    service.revoke(ENV_ID, KEY_ID);

    assertThat(key.getRevokedAt()).isEqualTo(NOW);
  }

  @Test
  void revokeIsRejectedWhenTheKeyIsAlreadyRevoked() {
    EnvironmentApiKey key = activeKey();
    key.setRevokedAt(NOW.minusDays(1));
    when(apiKeyRepository.findById(KEY_ID)).thenReturn(Optional.of(key));

    assertThatThrownBy(() -> service.revoke(ENV_ID, KEY_ID))
        .isInstanceOf(DuplicateResourceException.class)
        .hasMessageContaining("already revoked");
  }

  @Test
  void revokeRejectsAKeyBelongingToAnotherEnvironment() {
    EnvironmentApiKey foreign = activeKey();
    foreign.setEnvironment(otherEnvironment());
    when(apiKeyRepository.findById(KEY_ID)).thenReturn(Optional.of(foreign));

    // 404, not 403: a guessed key id must not confirm that the key exists elsewhere.
    assertThatThrownBy(() -> service.revoke(ENV_ID, KEY_ID))
        .isInstanceOf(ResourceNotFoundException.class);
    // Authorization is against the environment in the URL, never the key's own environment.
    verify(permissionService)
        .check(eq(Action.ENV_KEY_REVOKE), argThat(ref -> ref.environment() == environment));
  }

  @Test
  void revokeAuthorizesBeforeLookingTheKeyUp() {
    // Check first, then look up: a caller without access to this environment gets the same 403
    // whether or not the key id belongs to it, so the response cannot confirm key membership.
    doThrow(new UnauthorizedException("nope")).when(permissionService).check(any(), any());

    assertThatThrownBy(() -> service.revoke(ENV_ID, KEY_ID))
        .isInstanceOf(UnauthorizedException.class);
    verify(apiKeyRepository, never()).findById(any());
  }

  @Test
  void rotateAuthorizesBeforeLookingTheKeyUp() {
    doThrow(new UnauthorizedException("nope")).when(permissionService).check(any(), any());

    assertThatThrownBy(() -> service.rotate(ENV_ID, KEY_ID, grace(0)))
        .isInstanceOf(UnauthorizedException.class);
    verify(apiKeyRepository, never()).findById(any());
  }

  @Test
  void revokeChecksEnvKeyRevokeAgainstTheTargetEnvironment() {
    when(apiKeyRepository.findById(KEY_ID)).thenReturn(Optional.of(activeKey()));

    service.revoke(ENV_ID, KEY_ID);

    verify(permissionService)
        .check(eq(Action.ENV_KEY_REVOKE), argThat(ref -> ref.environment() == environment));
  }

  private RotateApiKeyRequest grace(int hours) {
    RotateApiKeyRequest request = new RotateApiKeyRequest();
    request.setGraceHours(hours);
    return request;
  }

  @Test
  void rotateWithGraceLeavesTheOldKeyValidUntilTheDeadline() {
    EnvironmentApiKey old = activeKey();
    when(apiKeyRepository.findById(KEY_ID)).thenReturn(Optional.of(old));

    service.rotate(ENV_ID, KEY_ID, grace(24));

    assertThat(old.getRevokedAt()).isNull();
    assertThat(old.getExpiresAt()).isEqualTo(NOW.plusHours(24));
    assertThat(old.isActive(fixedClock)).isTrue();
  }

  @Test
  void rotateWithGraceReArmsExpiryWarningsForTheOldKeysNewDeadline() {
    EnvironmentApiKey old = activeKey();
    old.setExpiresAt(NOW.plusDays(60));
    old.setExpiryNoticeSentDays(30);
    when(apiKeyRepository.findById(KEY_ID)).thenReturn(Optional.of(old));

    service.rotate(ENV_ID, KEY_ID, grace(24));

    assertThat(old.getExpiresAt()).isEqualTo(NOW.plusHours(24));
    assertThat(old.getExpiryNoticeSentDays()).isNull();
  }

  @Test
  void aGracePeriodNeverExtendsTheOldKeysOwnDeadline() {
    // A key due in 5 days rotated with 30 days of grace keeps its 5-day deadline. Before this, the
    // grace deadline replaced it outright, so rotation could lengthen a credential's life.
    EnvironmentApiKey old = activeKey();
    old.setExpiresAt(NOW.plusDays(5));
    old.setExpiryNoticeSentDays(7);
    when(apiKeyRepository.findById(KEY_ID)).thenReturn(Optional.of(old));

    service.rotate(ENV_ID, KEY_ID, grace(720));

    assertThat(old.getExpiresAt()).isEqualTo(NOW.plusDays(5));
    // The deadline did not move, so the warnings already sent for it still stand.
    assertThat(old.getExpiryNoticeSentDays()).isEqualTo(7);
  }

  @Test
  void aKeyCanOnlyBeRotatedOnce() {
    // Rotating the same (grace-kept) key again used to push its deadline out another grace period
    // and mint another active key, indefinitely. The replaced key is now marked and refused.
    EnvironmentApiKey old = activeKey();
    when(apiKeyRepository.findById(KEY_ID)).thenReturn(Optional.of(old));

    service.rotate(ENV_ID, KEY_ID, grace(24));
    assertThat(old.getRotatedAt()).isEqualTo(NOW);

    assertThatThrownBy(() -> service.rotate(ENV_ID, KEY_ID, grace(24)))
        .isInstanceOf(DuplicateResourceException.class)
        .hasMessageContaining("already been rotated");
    assertThat(old.getExpiresAt()).isEqualTo(NOW.plusHours(24));
  }

  @Test
  void aGraceRotationMayGoOneKeyOverTheCapButNoFurther() {
    when(apiKeyRepository.findById(KEY_ID)).thenReturn(Optional.of(activeKey()));

    // At the cap (10 active, the rotated key among them): allowed, briefly 11 during the grace.
    when(apiKeyRepository.countActiveByEnvironmentId(eq(ENV_ID), any())).thenReturn(10L);
    service.rotate(ENV_ID, KEY_ID, grace(24));

    // Already one over: another grace rotation would add a twelfth.
    when(apiKeyRepository.findById(KEY_ID)).thenReturn(Optional.of(activeKey()));
    when(apiKeyRepository.countActiveByEnvironmentId(eq(ENV_ID), any())).thenReturn(11L);
    assertThatThrownBy(() -> service.rotate(ENV_ID, KEY_ID, grace(24)))
        .isInstanceOf(DuplicateResourceException.class);

    // A hard cutover adds nothing, so it stays available even over the cap.
    service.rotate(ENV_ID, KEY_ID, grace(0));
  }

  @Test
  void rotateWithZeroGraceRevokesTheOldKeyImmediately() {
    EnvironmentApiKey old = activeKey();
    when(apiKeyRepository.findById(KEY_ID)).thenReturn(Optional.of(old));

    service.rotate(ENV_ID, KEY_ID, grace(0));

    assertThat(old.getRevokedAt()).isEqualTo(NOW);
    assertThat(old.isActive(fixedClock)).isFalse();
  }

  @Test
  void theRotatedKeyCarriesTheOldNameForward() {
    EnvironmentApiKey old = activeKey();
    old.setName("ios-app");
    when(apiKeyRepository.findById(KEY_ID)).thenReturn(Optional.of(old));

    service.rotate(ENV_ID, KEY_ID, grace(24));

    // Names are labels, not identifiers — a collision during the grace window is expected
    // and the two rows are told apart by prefix and creation time. Rotation saves the fresh
    // key first, then the updated old key — the fresh one is the first save() call.
    ArgumentCaptor<EnvironmentApiKey> captor = ArgumentCaptor.forClass(EnvironmentApiKey.class);
    verify(apiKeyRepository, times(2)).save(captor.capture());
    assertThat(captor.getAllValues().get(0).getName()).isEqualTo("ios-app");
  }

  @Test
  void rotateIssuesAFreshSecretNotTheOldOne() {
    EnvironmentApiKey old = activeKey();
    when(apiKeyRepository.findById(KEY_ID)).thenReturn(Optional.of(old));

    ApiKeySecretResponse response = service.rotate(ENV_ID, KEY_ID, grace(24));

    assertThat(ApiKeyHasher.hash(response.getApiKey())).isNotEqualTo(old.getKeyHash());
  }

  @Test
  void rotateAuthorizesAsEnvRotateKeyNotAsCreatePlusRevoke() {
    // Decomposing rotation into ENV_KEY_CREATE + ENV_KEY_REVOKE would drag it into the
    // revoke window exemption, which rotation must not have: issuing a new production
    // credential is a planned change and stays fully windowed.
    when(apiKeyRepository.findById(KEY_ID)).thenReturn(Optional.of(activeKey()));

    service.rotate(ENV_ID, KEY_ID, grace(24));

    verify(permissionService)
        .check(eq(Action.ENV_ROTATE_KEY), argThat(ref -> ref.environment() == environment));
    verify(permissionService, never()).check(eq(Action.ENV_KEY_REVOKE), any());
  }

  @Test
  void rotatingAnAlreadyRevokedKeyIsRejected() {
    EnvironmentApiKey old = activeKey();
    old.setRevokedAt(NOW.minusDays(1));
    when(apiKeyRepository.findById(KEY_ID)).thenReturn(Optional.of(old));

    assertThatThrownBy(() -> service.rotate(ENV_ID, KEY_ID, grace(24)))
        .isInstanceOf(DuplicateResourceException.class)
        .hasMessageContaining("revoked");
  }

  @Test
  void rotatingAnExpiredButUnrevokedKeyIsRejectedWithoutExtendingItsExpiry() {
    // An expired key is already dead per isActive(): !isRevoked() && !isExpired(clock). Grace
    // must not resurrect it by pushing its past expiresAt into the future.
    LocalDateTime pastExpiry = NOW.minusDays(1);
    EnvironmentApiKey old = keyExpiringAt(pastExpiry);
    when(apiKeyRepository.findById(KEY_ID)).thenReturn(Optional.of(old));

    assertThatThrownBy(() -> service.rotate(ENV_ID, KEY_ID, grace(24)))
        .isInstanceOf(DuplicateResourceException.class)
        .hasMessageContaining("expired");
    assertThat(old.getExpiresAt()).isEqualTo(pastExpiry);
    verify(apiKeyRepository, never()).save(any());
  }

  @Test
  void rotateGivesTheFreshKeyAFullLifetimeOfTheSameLength() {
    // A 30-day key created 23 days ago, rotated with 7 days left. Inheriting the old deadline
    // would give the replacement 7 days — the very expiry the operator is rotating to escape.
    EnvironmentApiKey old = activeKey();
    old.setCreatedAt(NOW.minusDays(23));
    old.setExpiresAt(NOW.plusDays(7));
    when(apiKeyRepository.findById(KEY_ID)).thenReturn(Optional.of(old));

    service.rotate(ENV_ID, KEY_ID, grace(24));

    ArgumentCaptor<EnvironmentApiKey> captor = ArgumentCaptor.forClass(EnvironmentApiKey.class);
    verify(apiKeyRepository, times(2)).save(captor.capture());
    assertThat(captor.getAllValues().get(0).getExpiresAt()).isEqualTo(NOW.plusDays(30));
  }

  @Test
  void rotatingANeverExpiringKeyYieldsANeverExpiringKey() {
    when(apiKeyRepository.findById(KEY_ID)).thenReturn(Optional.of(activeKey()));

    service.rotate(ENV_ID, KEY_ID, grace(24));

    ArgumentCaptor<EnvironmentApiKey> captor = ArgumentCaptor.forClass(EnvironmentApiKey.class);
    verify(apiKeyRepository, times(2)).save(captor.capture());
    assertThat(captor.getAllValues().get(0).getExpiresAt()).isNull();
  }

  @Test
  void oldKeyBecomesInactiveOncePastTheGraceDeadline() {
    EnvironmentApiKey old = activeKey();
    when(apiKeyRepository.findById(KEY_ID)).thenReturn(Optional.of(old));

    service.rotate(ENV_ID, KEY_ID, grace(24));

    // isExpired's boundary is closed: exactly at the deadline the key is already expired.
    Clock atDeadline =
        Clock.fixed(
            NOW.plusHours(24).atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
    assertThat(old.isActive(atDeadline)).isFalse();
  }
}
