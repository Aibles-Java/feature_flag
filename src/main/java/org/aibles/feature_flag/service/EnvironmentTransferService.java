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

  ImportResultResponse importSnapshot(UUID environmentId, ImportEnvironmentRequest request);
}
