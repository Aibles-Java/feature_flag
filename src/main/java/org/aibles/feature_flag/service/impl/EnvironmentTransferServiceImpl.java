package org.aibles.feature_flag.service.impl;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.domain.entity.FeatureFlag;
import org.aibles.feature_flag.domain.entity.FlagEnvironmentState;
import org.aibles.feature_flag.domain.entity.Project;
import org.aibles.feature_flag.domain.enums.Action;
import org.aibles.feature_flag.domain.enums.AuditAction;
import org.aibles.feature_flag.domain.enums.AuditEntityType;
import org.aibles.feature_flag.domain.enums.ImportConflictStrategy;
import org.aibles.feature_flag.domain.enums.ImportOutcome;
import org.aibles.feature_flag.domain.enums.MemberRole;
import org.aibles.feature_flag.dto.request.CloneEnvironmentRequest;
import org.aibles.feature_flag.dto.request.ImportEnvironmentRequest;
import org.aibles.feature_flag.dto.response.EnvironmentResponse;
import org.aibles.feature_flag.dto.response.EnvironmentSecretResponse;
import org.aibles.feature_flag.dto.response.EnvironmentSnapshotResponse;
import org.aibles.feature_flag.dto.response.ImportResultResponse;
import org.aibles.feature_flag.exception.DuplicateResourceException;
import org.aibles.feature_flag.exception.InvalidRequestException;
import org.aibles.feature_flag.exception.ResourceNotFoundException;
import org.aibles.feature_flag.repository.EnvironmentRepository;
import org.aibles.feature_flag.repository.FeatureFlagRepository;
import org.aibles.feature_flag.repository.FlagEnvironmentStateRepository;
import org.aibles.feature_flag.service.EnvironmentTransferService;
import org.aibles.feature_flag.util.ApiKeyGenerator;
import org.aibles.feature_flag.util.ApiKeyHasher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Clone / export / import of an environment's flag configuration (issue #38). */
@Service
@RequiredArgsConstructor
public class EnvironmentTransferServiceImpl implements EnvironmentTransferService {

  private final EnvironmentRepository environmentRepository;
  private final FeatureFlagRepository featureFlagRepository;
  private final FlagEnvironmentStateRepository flagStateRepository;
  private final PermissionService permissionService;
  private final AuditService auditService;

  @Override
  @Transactional
  public EnvironmentSecretResponse clone(
      UUID sourceEnvironmentId, CloneEnvironmentRequest request) {
    permissionService.requireRoleForEnvironment(
        sourceEnvironmentId, MemberRole.OWNER, MemberRole.ADMIN);
    Environment source = findEnvironment(sourceEnvironmentId);
    Project project = source.getProject();

    if (environmentRepository.existsByProjectIdAndName(project.getId(), request.getName())) {
      throw new DuplicateResourceException("Environment name already exists in this project");
    }

    // A clone is a new environment, so it gets its own key — copying the source's would silently
    // widen the blast radius of a leaked key across two environments.
    String plaintextKey = ApiKeyGenerator.generate();
    Environment target =
        environmentRepository.save(
            Environment.builder()
                .project(project)
                .name(request.getName())
                .description(request.getDescription())
                .apiKeyHash(ApiKeyHasher.hash(plaintextKey))
                .build());

    for (FlagEnvironmentState state :
        flagStateRepository.findAllByEnvironmentIdOrderByFlagKey(sourceEnvironmentId)) {
      flagStateRepository.save(
          FlagEnvironmentState.builder()
              .featureFlag(state.getFeatureFlag())
              .environment(target)
              .enabled(state.isEnabled())
              .value(state.getValue())
              .rolloutPercent(state.getRolloutPercent())
              .build());
    }

    // before = the environment this was cloned from, after = the clone. Neither view carries the
    // plaintext key.
    auditService.record(
        AuditEntityType.ENVIRONMENT,
        target.getId(),
        AuditAction.CLONE,
        project.getOrganization().getId(),
        toResponse(source),
        toResponse(target));

    return EnvironmentSecretResponse.builder()
        .id(target.getId())
        .name(target.getName())
        .description(target.getDescription())
        .projectId(project.getId())
        .apiKey(plaintextKey)
        .createdAt(target.getCreatedAt())
        .build();
  }

  @Override
  @Transactional(readOnly = true)
  public EnvironmentSnapshotResponse export(UUID environmentId) {
    permissionService.requireRoleForEnvironment(environmentId, MemberRole.OWNER, MemberRole.ADMIN);
    Environment env = findEnvironment(environmentId);

    List<EnvironmentSnapshotResponse.FlagSnapshot> flags =
        flagStateRepository.findAllByEnvironmentIdOrderByFlagKey(environmentId).stream()
            .map(EnvironmentTransferServiceImpl::toFlagSnapshot)
            .toList();

    return EnvironmentSnapshotResponse.builder()
        .schemaVersion(EnvironmentSnapshotResponse.SCHEMA_VERSION)
        .exportedAt(LocalDateTime.now())
        .environmentId(env.getId())
        .environmentName(env.getName())
        .projectId(env.getProject().getId())
        .flags(flags)
        .build();
  }

  @Override
  @Transactional
  public ImportResultResponse importSnapshot(UUID environmentId, ImportEnvironmentRequest request) {
    Environment target = findEnvironment(environmentId);
    Project project = target.getProject();
    boolean dryRun = request.isDryRun();

    // Import creates flags and writes their state, so it is authorized as exactly that rather
    // than through the pre-ABAC role adapter. Attaching the target environment is what subjects a
    // real import to the production rules (ADR-0006 rule B/D): without it, an ADMIN denied
    // PUT /flags/{id}/environments/{prodEnvId} could flip the same flag by importing a snapshot.
    // A dry run writes nothing, so it stays project-scoped and no change window applies to it.
    permissionService.check(
        Action.FLAG_CREATE, PermissionService.ResourceRef.project(project.getId()));
    permissionService.check(
        Action.FLAG_STATE_UPDATE,
        dryRun
            ? PermissionService.ResourceRef.project(project.getId())
            : PermissionService.ResourceRef.environment(project.getId(), target));

    ImportEnvironmentRequest.Snapshot snapshot = request.getSnapshot();
    validateSchemaVersion(snapshot.getSchemaVersion());
    validateNoDuplicateKeys(snapshot.getFlags());

    ImportConflictStrategy strategy = request.getConflictStrategy();
    List<ImportResultResponse.ItemResult> items = new ArrayList<>();

    for (ImportEnvironmentRequest.FlagEntry entry : snapshot.getFlags()) {
      items.add(applyEntry(project, target, entry, strategy, dryRun));
    }

    ImportResultResponse result =
        ImportResultResponse.builder()
            .dryRun(dryRun)
            .conflictStrategy(strategy)
            .schemaVersion(snapshot.getSchemaVersion())
            .summary(summarize(items))
            .items(items)
            .build();

    boolean changed = result.getSummary().getCreated() > 0 || result.getSummary().getUpdated() > 0;
    if (!dryRun && changed) {
      auditService.record(
          AuditEntityType.ENVIRONMENT,
          environmentId,
          AuditAction.IMPORT,
          project.getOrganization().getId(),
          null,
          result.getSummary());
    }
    return result;
  }

  /**
   * Reconciles one snapshot entry against the target environment. A missing flag is created (with
   * its state rows across every environment of the project, matching flag-creation behaviour); an
   * existing flag has only its state in <em>this</em> environment touched — name, description,
   * archived and value type are project-wide properties that an environment-scoped import must not
   * rewrite behind the other environments' backs.
   */
  private ImportResultResponse.ItemResult applyEntry(
      Project project,
      Environment target,
      ImportEnvironmentRequest.FlagEntry entry,
      ImportConflictStrategy strategy,
      boolean dryRun) {

    Optional<FeatureFlag> existing =
        featureFlagRepository.findByProjectIdAndKey(project.getId(), entry.getKey());

    if (existing.isEmpty()) {
      if (!dryRun) {
        createFlagWithStates(project, target, entry);
      }
      return item(entry, ImportOutcome.CREATED, "flag and state created");
    }

    FeatureFlag flag = existing.get();
    if (flag.getValueType() != entry.getValueType()) {
      return item(
          entry,
          ImportOutcome.SKIPPED,
          "value type mismatch: existing="
              + flag.getValueType()
              + ", snapshot="
              + entry.getValueType());
    }

    Optional<FlagEnvironmentState> currentState =
        flagStateRepository.findByFeatureFlagIdAndEnvironmentId(flag.getId(), target.getId());

    if (currentState.isEmpty()) {
      // The flag predates this environment (states are only backfilled at flag-creation time), so
      // there is no existing value to conflict with — create the row whatever the strategy.
      if (!dryRun) {
        flagStateRepository.save(newState(flag, target, entry));
      }
      return item(entry, ImportOutcome.CREATED, "flag state created");
    }

    FlagEnvironmentState state = currentState.get();
    if (matches(state, entry)) {
      return item(entry, ImportOutcome.UNCHANGED, null);
    }
    if (strategy == ImportConflictStrategy.SKIP) {
      return item(entry, ImportOutcome.SKIPPED, "state differs and conflict strategy is SKIP");
    }

    if (!dryRun) {
      state.setEnabled(enabled(entry));
      state.setValue(entry.getValue());
      state.setRolloutPercent(rolloutPercent(entry));
      flagStateRepository.save(state);
    }
    return item(entry, ImportOutcome.UPDATED, "state overwritten");
  }

  private void createFlagWithStates(
      Project project, Environment target, ImportEnvironmentRequest.FlagEntry entry) {
    FeatureFlag flag =
        featureFlagRepository.save(
            FeatureFlag.builder()
                .project(project)
                .name(entry.getName() != null ? entry.getName() : entry.getKey())
                .key(entry.getKey())
                .description(entry.getDescription())
                .valueType(entry.getValueType())
                .archived(Boolean.TRUE.equals(entry.getArchived()))
                .build());

    for (Environment env : environmentRepository.findAllByProjectId(project.getId())) {
      // The target environment gets the snapshot's state; every sibling gets the same disabled
      // default a normally created flag would receive.
      flagStateRepository.save(
          env.getId().equals(target.getId())
              ? newState(flag, env, entry)
              : FlagEnvironmentState.builder().featureFlag(flag).environment(env).build());
    }
  }

  private static FlagEnvironmentState newState(
      FeatureFlag flag, Environment env, ImportEnvironmentRequest.FlagEntry entry) {
    return FlagEnvironmentState.builder()
        .featureFlag(flag)
        .environment(env)
        .enabled(enabled(entry))
        .value(entry.getValue())
        .rolloutPercent(rolloutPercent(entry))
        .build();
  }

  private static boolean matches(
      FlagEnvironmentState state, ImportEnvironmentRequest.FlagEntry entry) {
    return state.isEnabled() == enabled(entry)
        && Objects.equals(state.getValue(), entry.getValue())
        && state.getRolloutPercent() == rolloutPercent(entry);
  }

  private static boolean enabled(ImportEnvironmentRequest.FlagEntry entry) {
    return Boolean.TRUE.equals(entry.getEnabled());
  }

  /** Mirrors the entity default so an entry that omits the field means "full rollout". */
  private static int rolloutPercent(ImportEnvironmentRequest.FlagEntry entry) {
    return entry.getRolloutPercent() != null ? entry.getRolloutPercent() : 100;
  }

  private void validateSchemaVersion(Integer schemaVersion) {
    if (schemaVersion == null || schemaVersion != EnvironmentSnapshotResponse.SCHEMA_VERSION) {
      throw new InvalidRequestException(
          "Unsupported snapshot schemaVersion: "
              + schemaVersion
              + " (supported: "
              + EnvironmentSnapshotResponse.SCHEMA_VERSION
              + ")");
    }
  }

  /** Two entries for one key would make the outcome depend on iteration order — reject up front. */
  private void validateNoDuplicateKeys(List<ImportEnvironmentRequest.FlagEntry> flags) {
    Set<String> seen = new HashSet<>();
    for (ImportEnvironmentRequest.FlagEntry entry : flags) {
      if (!seen.add(entry.getKey())) {
        throw new InvalidRequestException("Duplicate flag key in snapshot: " + entry.getKey());
      }
    }
  }

  private static ImportResultResponse.ItemResult item(
      ImportEnvironmentRequest.FlagEntry entry, ImportOutcome outcome, String detail) {
    return ImportResultResponse.ItemResult.builder()
        .flagKey(entry.getKey())
        .outcome(outcome)
        .detail(detail)
        .build();
  }

  private static ImportResultResponse.Summary summarize(
      List<ImportResultResponse.ItemResult> items) {
    return ImportResultResponse.Summary.builder()
        .created(count(items, ImportOutcome.CREATED))
        .updated(count(items, ImportOutcome.UPDATED))
        .unchanged(count(items, ImportOutcome.UNCHANGED))
        .skipped(count(items, ImportOutcome.SKIPPED))
        .build();
  }

  private static int count(List<ImportResultResponse.ItemResult> items, ImportOutcome outcome) {
    return (int) items.stream().filter(i -> i.getOutcome() == outcome).count();
  }

  private static EnvironmentSnapshotResponse.FlagSnapshot toFlagSnapshot(
      FlagEnvironmentState state) {
    FeatureFlag flag = state.getFeatureFlag();
    return EnvironmentSnapshotResponse.FlagSnapshot.builder()
        .key(flag.getKey())
        .name(flag.getName())
        .description(flag.getDescription())
        .valueType(flag.getValueType())
        .archived(flag.isArchived())
        .enabled(state.isEnabled())
        .value(state.getValue())
        .rolloutPercent(state.getRolloutPercent())
        .build();
  }

  private Environment findEnvironment(UUID id) {
    return environmentRepository
        .findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Environment", id));
  }

  private static EnvironmentResponse toResponse(Environment env) {
    return EnvironmentResponse.builder()
        .id(env.getId())
        .name(env.getName())
        .description(env.getDescription())
        .projectId(env.getProject().getId())
        .createdAt(env.getCreatedAt())
        .build();
  }
}
