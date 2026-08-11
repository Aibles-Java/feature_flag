package org.aibles.feature_flag.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.domain.entity.FeatureFlag;
import org.aibles.feature_flag.domain.entity.FlagEnvironmentState;
import org.aibles.feature_flag.domain.entity.Organization;
import org.aibles.feature_flag.domain.entity.Project;
import org.aibles.feature_flag.domain.enums.AuditAction;
import org.aibles.feature_flag.domain.enums.AuditEntityType;
import org.aibles.feature_flag.domain.enums.FlagValueType;
import org.aibles.feature_flag.domain.enums.ImportConflictStrategy;
import org.aibles.feature_flag.domain.enums.ImportOutcome;
import org.aibles.feature_flag.domain.enums.MemberRole;
import org.aibles.feature_flag.dto.request.CloneEnvironmentRequest;
import org.aibles.feature_flag.dto.request.ImportEnvironmentRequest;
import org.aibles.feature_flag.dto.response.EnvironmentSecretResponse;
import org.aibles.feature_flag.dto.response.EnvironmentSnapshotResponse;
import org.aibles.feature_flag.dto.response.ImportResultResponse;
import org.aibles.feature_flag.exception.DuplicateResourceException;
import org.aibles.feature_flag.exception.InvalidRequestException;
import org.aibles.feature_flag.exception.ResourceNotFoundException;
import org.aibles.feature_flag.repository.EnvironmentRepository;
import org.aibles.feature_flag.repository.FeatureFlagRepository;
import org.aibles.feature_flag.repository.FlagEnvironmentStateRepository;
import org.aibles.feature_flag.util.ApiKeyHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EnvironmentTransferServiceImplTest {

  @Mock EnvironmentRepository environmentRepository;
  @Mock FeatureFlagRepository featureFlagRepository;
  @Mock FlagEnvironmentStateRepository flagStateRepository;
  @Mock PermissionService permissionService;
  @Mock AuditService auditService;

  EnvironmentTransferServiceImpl service;

  final ObjectMapper objectMapper =
      new ObjectMapper()
          .findAndRegisterModules()
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  UUID orgId = UUID.randomUUID();
  UUID projectId = UUID.randomUUID();
  UUID sourceEnvId = UUID.randomUUID();
  UUID targetEnvId = UUID.randomUUID();

  Project project;
  Environment sourceEnv;
  Environment targetEnv;

  FeatureFlag boolFlag;
  FeatureFlag stringFlag;
  FeatureFlag archivedFlag;

  @BeforeEach
  void setUp() {
    service =
        new EnvironmentTransferServiceImpl(
            environmentRepository,
            featureFlagRepository,
            flagStateRepository,
            permissionService,
            auditService);

    Organization org = Organization.builder().id(orgId).name("org").build();
    project = Project.builder().id(projectId).organization(org).name("proj").build();
    sourceEnv =
        Environment.builder()
            .id(sourceEnvId)
            .project(project)
            .name("production")
            .apiKeyHash(ApiKeyHasher.hash("source-key"))
            .build();
    targetEnv =
        Environment.builder()
            .id(targetEnvId)
            .project(project)
            .name("staging")
            .apiKeyHash(ApiKeyHasher.hash("target-key"))
            .build();

    boolFlag = flag("checkout-v2", "Checkout v2", FlagValueType.BOOLEAN, false);
    stringFlag = flag("banner-text", "Banner text", FlagValueType.STRING, false);
    archivedFlag = flag("legacy-cart", "Legacy cart", FlagValueType.BOOLEAN, true);

    when(environmentRepository.findById(sourceEnvId)).thenReturn(Optional.of(sourceEnv));
    when(environmentRepository.findById(targetEnvId)).thenReturn(Optional.of(targetEnv));
    when(environmentRepository.findAllByProjectId(projectId))
        .thenReturn(List.of(sourceEnv, targetEnv));
    when(environmentRepository.save(any(Environment.class)))
        .thenAnswer(
            inv -> {
              Environment e = inv.getArgument(0);
              if (e.getId() == null) e.setId(UUID.randomUUID());
              return e;
            });
    when(featureFlagRepository.save(any(FeatureFlag.class)))
        .thenAnswer(
            inv -> {
              FeatureFlag f = inv.getArgument(0);
              if (f.getId() == null) f.setId(UUID.randomUUID());
              return f;
            });
    when(flagStateRepository.save(any(FlagEnvironmentState.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    doNothing().when(permissionService).requireRoleForEnvironment(any(), any(MemberRole[].class));
  }

  // ---------------------------------------------------------------- clone

  @Test
  void clone_copiesEveryStateAndMintsAFreshApiKey() {
    when(flagStateRepository.findAllByEnvironmentIdOrderByFlagKey(sourceEnvId))
        .thenReturn(
            List.of(
                state(stringFlag, sourceEnv, true, "hello", 30),
                state(boolFlag, sourceEnv, true, null, 100)));

    CloneEnvironmentRequest request = new CloneEnvironmentRequest();
    request.setName("production-copy");
    request.setDescription("clone for the migration rehearsal");

    EnvironmentSecretResponse response = service.clone(sourceEnvId, request);

    assertThat(response.getName()).isEqualTo("production-copy");
    assertThat(response.getProjectId()).isEqualTo(projectId);
    assertThat(response.getApiKey()).hasSize(64);

    ArgumentCaptor<Environment> envCaptor = ArgumentCaptor.forClass(Environment.class);
    verify(environmentRepository).save(envCaptor.capture());
    Environment created = envCaptor.getValue();
    // Fresh key: hashed, never copied from the source, and never returned in hashed form.
    assertThat(created.getApiKeyHash()).isEqualTo(ApiKeyHasher.hash(response.getApiKey()));
    assertThat(created.getApiKeyHash()).isNotEqualTo(sourceEnv.getApiKeyHash());

    ArgumentCaptor<FlagEnvironmentState> stateCaptor =
        ArgumentCaptor.forClass(FlagEnvironmentState.class);
    verify(flagStateRepository, times(2)).save(stateCaptor.capture());
    List<FlagEnvironmentState> copies = stateCaptor.getAllValues();
    assertThat(copies)
        .allSatisfy(s -> assertThat(s.getEnvironment()).isSameAs(created))
        .extracting(
            s -> s.getFeatureFlag().getKey(),
            FlagEnvironmentState::isEnabled,
            FlagEnvironmentState::getValue,
            FlagEnvironmentState::getRolloutPercent)
        .containsExactly(
            tuple("banner-text", true, "hello", 30), tuple("checkout-v2", true, null, 100));
  }

  @Test
  void clone_recordsAuditWithSourceAsBefore() {
    when(flagStateRepository.findAllByEnvironmentIdOrderByFlagKey(sourceEnvId))
        .thenReturn(List.of());
    CloneEnvironmentRequest request = new CloneEnvironmentRequest();
    request.setName("production-copy");

    service.clone(sourceEnvId, request);

    verify(auditService)
        .record(
            eq(AuditEntityType.ENVIRONMENT),
            any(UUID.class),
            eq(AuditAction.CLONE),
            eq(orgId),
            any(),
            any());
  }

  @Test
  void clone_rejectsDuplicateEnvironmentName() {
    when(environmentRepository.existsByProjectIdAndName(projectId, "staging")).thenReturn(true);
    CloneEnvironmentRequest request = new CloneEnvironmentRequest();
    request.setName("staging");

    assertThatThrownBy(() -> service.clone(sourceEnvId, request))
        .isInstanceOf(DuplicateResourceException.class);
    verify(environmentRepository, never()).save(any());
  }

  @Test
  void clone_requiresOwnerOrAdmin() {
    when(flagStateRepository.findAllByEnvironmentIdOrderByFlagKey(sourceEnvId))
        .thenReturn(List.of());
    CloneEnvironmentRequest request = new CloneEnvironmentRequest();
    request.setName("production-copy");

    service.clone(sourceEnvId, request);

    verify(permissionService)
        .requireRoleForEnvironment(sourceEnvId, MemberRole.OWNER, MemberRole.ADMIN);
  }

  @Test
  void clone_throwsWhenSourceEnvironmentMissing() {
    UUID unknown = UUID.randomUUID();
    when(environmentRepository.findById(unknown)).thenReturn(Optional.empty());
    CloneEnvironmentRequest request = new CloneEnvironmentRequest();
    request.setName("whatever");

    assertThatThrownBy(() -> service.clone(unknown, request))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  // --------------------------------------------------------------- export

  @Test
  void export_returnsSchemaVersionedEnvelopeIncludingArchivedFlags() {
    when(flagStateRepository.findAllByEnvironmentIdOrderByFlagKey(sourceEnvId))
        .thenReturn(
            List.of(
                state(stringFlag, sourceEnv, true, "hello", 30),
                state(archivedFlag, sourceEnv, false, null, 100)));

    EnvironmentSnapshotResponse snapshot = service.export(sourceEnvId);

    assertThat(snapshot.getSchemaVersion()).isEqualTo(EnvironmentSnapshotResponse.SCHEMA_VERSION);
    assertThat(snapshot.getEnvironmentId()).isEqualTo(sourceEnvId);
    assertThat(snapshot.getEnvironmentName()).isEqualTo("production");
    assertThat(snapshot.getProjectId()).isEqualTo(projectId);
    assertThat(snapshot.getExportedAt()).isNotNull();
    assertThat(snapshot.getFlags())
        .extracting(
            EnvironmentSnapshotResponse.FlagSnapshot::getKey,
            EnvironmentSnapshotResponse.FlagSnapshot::isArchived,
            EnvironmentSnapshotResponse.FlagSnapshot::isEnabled,
            EnvironmentSnapshotResponse.FlagSnapshot::getValue,
            EnvironmentSnapshotResponse.FlagSnapshot::getRolloutPercent)
        .containsExactly(
            tuple("banner-text", false, true, "hello", 30),
            tuple("legacy-cart", true, false, null, 100));
    verify(permissionService)
        .requireRoleForEnvironment(sourceEnvId, MemberRole.OWNER, MemberRole.ADMIN);
  }

  /**
   * AC: export → import is lossless. The snapshot is pushed through real JSON on the way back in,
   * so this also pins the wire compatibility between the export envelope and the import body.
   */
  @Test
  void exportThenImportIntoAnotherEnvironment_isLossless() throws Exception {
    List<FlagEnvironmentState> sourceStates =
        List.of(
            state(stringFlag, sourceEnv, true, "hello", 30),
            state(boolFlag, sourceEnv, true, null, 55),
            state(archivedFlag, sourceEnv, false, null, 100));
    // The target starts at the defaults every environment gets.
    List<FlagEnvironmentState> targetStates =
        new ArrayList<>(
            List.of(
                state(stringFlag, targetEnv, false, null, 100),
                state(boolFlag, targetEnv, false, null, 100),
                state(archivedFlag, targetEnv, false, null, 100)));

    when(flagStateRepository.findAllByEnvironmentIdOrderByFlagKey(sourceEnvId))
        .thenReturn(sourceStates);
    when(flagStateRepository.findAllByEnvironmentIdOrderByFlagKey(targetEnvId))
        .thenReturn(targetStates);
    stubExistingFlags(boolFlag, stringFlag, archivedFlag);
    stubTargetStates(targetStates);

    EnvironmentSnapshotResponse exported = service.export(sourceEnvId);

    ImportEnvironmentRequest request = new ImportEnvironmentRequest();
    request.setConflictStrategy(ImportConflictStrategy.OVERWRITE);
    request.setSnapshot(
        objectMapper.readValue(
            objectMapper.writeValueAsString(exported), ImportEnvironmentRequest.Snapshot.class));

    ImportResultResponse result = service.importSnapshot(targetEnvId, request);

    assertThat(result.getSummary().getUpdated()).isEqualTo(2); // archived flag already matched
    assertThat(result.getSummary().getUnchanged()).isEqualTo(1);

    EnvironmentSnapshotResponse reExported = service.export(targetEnvId);
    assertThat(reExported.getFlags()).isEqualTo(exported.getFlags());
  }

  // --------------------------------------------------------------- import

  @Test
  void import_dryRun_reportsChangeSetWithoutWriting() {
    List<FlagEnvironmentState> targetStates = List.of(state(boolFlag, targetEnv, false, null, 100));
    stubExistingFlags(boolFlag);
    stubTargetStates(targetStates);

    ImportEnvironmentRequest request =
        importRequest(ImportConflictStrategy.OVERWRITE, entry(boolFlag, true, null, 100));
    request.setDryRun(true);

    ImportResultResponse result = service.importSnapshot(targetEnvId, request);

    assertThat(result.isDryRun()).isTrue();
    assertThat(result.getSummary().getUpdated()).isEqualTo(1);
    assertThat(result.getItems())
        .singleElement()
        .satisfies(
            i -> {
              assertThat(i.getFlagKey()).isEqualTo("checkout-v2");
              assertThat(i.getOutcome()).isEqualTo(ImportOutcome.UPDATED);
            });
    // Nothing written, and the in-memory entity is left exactly as it was.
    verify(flagStateRepository, never()).save(any());
    verify(featureFlagRepository, never()).save(any());
    verify(auditService, never()).record(any(), any(), any(), any(), any(), any());
    assertThat(targetStates.get(0).isEnabled()).isFalse();
  }

  @Test
  void import_rejectsUnknownSchemaVersion() {
    ImportEnvironmentRequest request =
        importRequest(ImportConflictStrategy.OVERWRITE, entry(boolFlag, true, null, 100));
    request.getSnapshot().setSchemaVersion(99);

    assertThatThrownBy(() -> service.importSnapshot(targetEnvId, request))
        .isInstanceOf(InvalidRequestException.class)
        .hasMessageContaining("99");
    verify(flagStateRepository, never()).save(any());
  }

  @Test
  void import_rejectsNullSchemaVersion() {
    ImportEnvironmentRequest request =
        importRequest(ImportConflictStrategy.OVERWRITE, entry(boolFlag, true, null, 100));
    request.getSnapshot().setSchemaVersion(null);

    assertThatThrownBy(() -> service.importSnapshot(targetEnvId, request))
        .isInstanceOf(InvalidRequestException.class);
  }

  @Test
  void import_rejectsDuplicateKeysInSnapshot() {
    ImportEnvironmentRequest request =
        importRequest(
            ImportConflictStrategy.OVERWRITE,
            entry(boolFlag, true, null, 100),
            entry(boolFlag, false, null, 100));

    assertThatThrownBy(() -> service.importSnapshot(targetEnvId, request))
        .isInstanceOf(InvalidRequestException.class)
        .hasMessageContaining("checkout-v2");
  }

  @Test
  void import_skipStrategy_leavesConflictingStateUntouched() {
    FlagEnvironmentState existing = state(boolFlag, targetEnv, false, null, 100);
    stubExistingFlags(boolFlag);
    stubTargetStates(List.of(existing));

    ImportResultResponse result =
        service.importSnapshot(
            targetEnvId,
            importRequest(ImportConflictStrategy.SKIP, entry(boolFlag, true, null, 100)));

    assertThat(result.getSummary().getSkipped()).isEqualTo(1);
    assertThat(result.getItems().get(0).getDetail()).contains("SKIP");
    assertThat(existing.isEnabled()).isFalse();
    verify(flagStateRepository, never()).save(any());
  }

  @Test
  void import_overwriteStrategy_appliesEnabledValueAndRollout() {
    FlagEnvironmentState existing = state(stringFlag, targetEnv, false, "old", 100);
    stubExistingFlags(stringFlag);
    stubTargetStates(List.of(existing));

    ImportResultResponse result =
        service.importSnapshot(
            targetEnvId,
            importRequest(ImportConflictStrategy.OVERWRITE, entry(stringFlag, true, "new", 25)));

    assertThat(result.getSummary().getUpdated()).isEqualTo(1);
    assertThat(existing.isEnabled()).isTrue();
    assertThat(existing.getValue()).isEqualTo("new");
    assertThat(existing.getRolloutPercent()).isEqualTo(25);
    verify(flagStateRepository).save(existing);
    verify(auditService)
        .record(
            eq(AuditEntityType.ENVIRONMENT),
            eq(targetEnvId),
            eq(AuditAction.IMPORT),
            eq(orgId),
            isNull(),
            any());
  }

  @Test
  void import_reportsUnchangedWhenStateAlreadyMatches() {
    FlagEnvironmentState existing = state(stringFlag, targetEnv, true, "same", 40);
    stubExistingFlags(stringFlag);
    stubTargetStates(List.of(existing));

    ImportResultResponse result =
        service.importSnapshot(
            targetEnvId,
            importRequest(ImportConflictStrategy.OVERWRITE, entry(stringFlag, true, "same", 40)));

    assertThat(result.getSummary().getUnchanged()).isEqualTo(1);
    verify(flagStateRepository, never()).save(any());
    // Nothing changed, so there is nothing to audit.
    verify(auditService, never()).record(any(), any(), any(), any(), any(), any());
  }

  @Test
  void import_createsMissingFlagWithStatesForEveryEnvironmentOfTheProject() {
    when(featureFlagRepository.findByProjectIdAndKey(projectId, "brand-new"))
        .thenReturn(Optional.empty());

    ImportEnvironmentRequest.FlagEntry entry = new ImportEnvironmentRequest.FlagEntry();
    entry.setKey("brand-new");
    entry.setName("Brand new");
    entry.setValueType(FlagValueType.STRING);
    entry.setEnabled(true);
    entry.setValue("v1");
    entry.setRolloutPercent(60);

    ImportResultResponse result =
        service.importSnapshot(targetEnvId, importRequest(ImportConflictStrategy.SKIP, entry));

    assertThat(result.getSummary().getCreated()).isEqualTo(1);

    ArgumentCaptor<FeatureFlag> flagCaptor = ArgumentCaptor.forClass(FeatureFlag.class);
    verify(featureFlagRepository).save(flagCaptor.capture());
    assertThat(flagCaptor.getValue().getKey()).isEqualTo("brand-new");
    assertThat(flagCaptor.getValue().getValueType()).isEqualTo(FlagValueType.STRING);

    ArgumentCaptor<FlagEnvironmentState> stateCaptor =
        ArgumentCaptor.forClass(FlagEnvironmentState.class);
    verify(flagStateRepository, times(2)).save(stateCaptor.capture());
    FlagEnvironmentState targetState =
        stateCaptor.getAllValues().stream()
            .filter(s -> s.getEnvironment().getId().equals(targetEnvId))
            .findFirst()
            .orElseThrow();
    FlagEnvironmentState siblingState =
        stateCaptor.getAllValues().stream()
            .filter(s -> s.getEnvironment().getId().equals(sourceEnvId))
            .findFirst()
            .orElseThrow();
    assertThat(targetState.isEnabled()).isTrue();
    assertThat(targetState.getValue()).isEqualTo("v1");
    assertThat(targetState.getRolloutPercent()).isEqualTo(60);
    // Siblings must not inherit the imported environment's state.
    assertThat(siblingState.isEnabled()).isFalse();
    assertThat(siblingState.getValue()).isNull();
  }

  @Test
  void import_createsStateRowWhenFlagPredatesTheEnvironment() {
    stubExistingFlags(boolFlag);
    when(flagStateRepository.findByFeatureFlagIdAndEnvironmentId(boolFlag.getId(), targetEnvId))
        .thenReturn(Optional.empty());

    ImportResultResponse result =
        service.importSnapshot(
            targetEnvId,
            importRequest(ImportConflictStrategy.SKIP, entry(boolFlag, true, null, 70)));

    assertThat(result.getSummary().getCreated()).isEqualTo(1);
    ArgumentCaptor<FlagEnvironmentState> captor =
        ArgumentCaptor.forClass(FlagEnvironmentState.class);
    verify(flagStateRepository).save(captor.capture());
    assertThat(captor.getValue().isEnabled()).isTrue();
    assertThat(captor.getValue().getRolloutPercent()).isEqualTo(70);
    verify(featureFlagRepository, never()).save(any());
  }

  @Test
  void import_skipsValueTypeMismatchRegardlessOfStrategy() {
    FlagEnvironmentState existing = state(boolFlag, targetEnv, false, null, 100);
    stubExistingFlags(boolFlag);
    stubTargetStates(List.of(existing));

    ImportEnvironmentRequest.FlagEntry entry = entry(boolFlag, true, "text", 100);
    entry.setValueType(FlagValueType.STRING); // existing flag is BOOLEAN

    ImportResultResponse result =
        service.importSnapshot(targetEnvId, importRequest(ImportConflictStrategy.OVERWRITE, entry));

    assertThat(result.getSummary().getSkipped()).isEqualTo(1);
    assertThat(result.getItems().get(0).getDetail()).contains("value type mismatch");
    verify(flagStateRepository, never()).save(any());
    assertThat(existing.isEnabled()).isFalse();
  }

  @Test
  void import_doesNotMutateExistingFlagMetadata() {
    FlagEnvironmentState existing = state(stringFlag, targetEnv, false, "old", 100);
    stubExistingFlags(stringFlag);
    stubTargetStates(List.of(existing));

    ImportEnvironmentRequest.FlagEntry entry = entry(stringFlag, true, "new", 100);
    entry.setName("Renamed by the snapshot");
    entry.setDescription("rewritten");
    entry.setArchived(true);

    service.importSnapshot(targetEnvId, importRequest(ImportConflictStrategy.OVERWRITE, entry));

    // Flag metadata is project-wide; an environment-scoped import only moves state.
    assertThat(stringFlag.getName()).isEqualTo("Banner text");
    assertThat(stringFlag.isArchived()).isFalse();
    verify(featureFlagRepository, never()).save(any());
  }

  @Test
  void import_requiresOwnerOrAdmin() {
    stubExistingFlags();
    service.importSnapshot(targetEnvId, importRequest(ImportConflictStrategy.SKIP));

    verify(permissionService)
        .requireRoleForEnvironment(targetEnvId, MemberRole.OWNER, MemberRole.ADMIN);
  }

  // --------------------------------------------------------------- helpers

  private FeatureFlag flag(String key, String name, FlagValueType type, boolean archived) {
    return FeatureFlag.builder()
        .id(UUID.randomUUID())
        .project(project)
        .key(key)
        .name(name)
        .valueType(type)
        .archived(archived)
        .build();
  }

  private FlagEnvironmentState state(
      FeatureFlag flag, Environment env, boolean enabled, String value, int rolloutPercent) {
    return FlagEnvironmentState.builder()
        .id(UUID.randomUUID())
        .featureFlag(flag)
        .environment(env)
        .enabled(enabled)
        .value(value)
        .rolloutPercent(rolloutPercent)
        .build();
  }

  private void stubExistingFlags(FeatureFlag... flags) {
    when(featureFlagRepository.findByProjectIdAndKey(eq(projectId), any()))
        .thenReturn(Optional.empty());
    for (FeatureFlag f : flags) {
      when(featureFlagRepository.findByProjectIdAndKey(projectId, f.getKey()))
          .thenReturn(Optional.of(f));
    }
  }

  private void stubTargetStates(List<FlagEnvironmentState> states) {
    for (FlagEnvironmentState s : states) {
      when(flagStateRepository.findByFeatureFlagIdAndEnvironmentId(
              s.getFeatureFlag().getId(), s.getEnvironment().getId()))
          .thenReturn(Optional.of(s));
    }
  }

  private ImportEnvironmentRequest.FlagEntry entry(
      FeatureFlag flag, boolean enabled, String value, int rolloutPercent) {
    ImportEnvironmentRequest.FlagEntry entry = new ImportEnvironmentRequest.FlagEntry();
    entry.setKey(flag.getKey());
    entry.setName(flag.getName());
    entry.setValueType(flag.getValueType());
    entry.setArchived(flag.isArchived());
    entry.setEnabled(enabled);
    entry.setValue(value);
    entry.setRolloutPercent(rolloutPercent);
    return entry;
  }

  private ImportEnvironmentRequest importRequest(
      ImportConflictStrategy strategy, ImportEnvironmentRequest.FlagEntry... entries) {
    ImportEnvironmentRequest.Snapshot snapshot = new ImportEnvironmentRequest.Snapshot();
    snapshot.setSchemaVersion(EnvironmentSnapshotResponse.SCHEMA_VERSION);
    snapshot.setFlags(List.of(entries));
    ImportEnvironmentRequest request = new ImportEnvironmentRequest();
    request.setConflictStrategy(strategy);
    request.setSnapshot(snapshot);
    return request;
  }
}
