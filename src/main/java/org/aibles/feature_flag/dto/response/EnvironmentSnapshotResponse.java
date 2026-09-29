package org.aibles.feature_flag.dto.response;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;
import org.aibles.feature_flag.domain.enums.FlagValueType;

/**
 * Schema-versioned snapshot of one environment's flags returned by {@code GET
 * /api/v1/environments/{id}/export} (issue #38).
 *
 * <p>The {@code flags} array is exactly what {@code POST /environments/{id}/import} consumes as its
 * {@code snapshot}, which is what makes an export → import round-trip lossless. Archived flags are
 * included so the snapshot describes the environment completely; entries are ordered by flag key so
 * two exports of the same state are byte-identical and diffable. The envelope carries no secrets —
 * an environment's API key is never part of a snapshot.
 */
@Data
@Builder
public class EnvironmentSnapshotResponse {

  /** Bumped whenever the snapshot shape changes incompatibly; imports reject anything else. */
  public static final int SCHEMA_VERSION = 1;

  private int schemaVersion;
  private LocalDateTime exportedAt;
  private UUID environmentId;
  private String environmentName;
  private UUID projectId;
  private List<FlagSnapshot> flags;

  /** One flag's definition plus its state in the exported environment. */
  @Data
  @Builder
  public static class FlagSnapshot {
    private String key;
    private String name;
    private String description;
    private FlagValueType valueType;
    private boolean archived;
    private boolean enabled;
    private String value;
    private int rolloutPercent;
  }
}
