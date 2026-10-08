package org.aibles.feature_flag.service;

import java.util.UUID;
import org.aibles.feature_flag.dto.request.CloneEnvironmentRequest;
import org.aibles.feature_flag.dto.request.ImportEnvironmentRequest;
import org.aibles.feature_flag.dto.response.EnvironmentSecretResponse;
import org.aibles.feature_flag.dto.response.EnvironmentSnapshotResponse;
import org.aibles.feature_flag.dto.response.ImportResultResponse;

/**
 * Moving flag configuration between environments: cloning an environment, exporting it as a
 * schema-versioned snapshot, and applying a snapshot back (issue #38). Every operation requires
 * OWNER or ADMIN on the owning organisation — export included, since a snapshot is the whole
 * environment's configuration in one payload.
 */
public interface EnvironmentTransferService {

  /**
   * Creates a sibling environment in the same project carrying a copy of every flag state, and
   * returns the new environment's plaintext API key exactly once — see {@link
   * EnvironmentSecretResponse}. The key is freshly generated; it is never copied from the source.
   */
  EnvironmentSecretResponse clone(UUID sourceEnvironmentId, CloneEnvironmentRequest request);

  EnvironmentSnapshotResponse export(UUID environmentId);

  /**
   * Applies a snapshot to {@code environmentId}, or only reports what it would do when {@code
   * dryRun}.
   *
   * <p>An entry whose {@code value} is too long or does not match its {@code valueType} is {@code
   * SKIPPED} with a detail starting "invalid value" (the value is never echoed), for an existing
   * flag with or without a state here and for a new flag alike. The check runs before the dry-run
   * split, so a dry run reports the identical item list a real run would (F23, S-0.6).
   *
   * <p>A real import into a PRODUCTION environment is OWNER-only and subject to the environment's
   * change window, exactly like {@code PUT /flags/{id}/environments/{envId}} (an ADMIN gets 403,
   * nothing is written). A window whose start hour equals its end hour (or an unset one) means
   * <em>unlimited</em> (D-15): it imposes no time restriction but never lifts the OWNER-only rule.
   * A dry run writes nothing and is project-scoped, so no window applies to it.
   */
  ImportResultResponse importSnapshot(UUID environmentId, ImportEnvironmentRequest request);
}
