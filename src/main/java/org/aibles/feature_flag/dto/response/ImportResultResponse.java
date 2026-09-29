package org.aibles.feature_flag.dto.response;

import java.util.List;
import lombok.Builder;
import lombok.Data;
import org.aibles.feature_flag.domain.enums.ImportConflictStrategy;
import org.aibles.feature_flag.domain.enums.ImportOutcome;

/**
 * The change set an import applied — or, when {@code dryRun} is true, the change set it <em>would
 * have</em> applied. Both modes compute and report identically; only the writes differ, so a
 * dry-run is a faithful preview of the real run.
 */
@Data
@Builder
public class ImportResultResponse {
  private boolean dryRun;
  private ImportConflictStrategy conflictStrategy;
  private int schemaVersion;
  private Summary summary;
  private List<ItemResult> items;

  @Data
  @Builder
  public static class Summary {
    private int created;
    private int updated;
    private int unchanged;
    private int skipped;
  }

  @Data
  @Builder
  public static class ItemResult {
    private String flagKey;
    private ImportOutcome outcome;

    /** Human-readable reason, populated for outcomes that need one (skips in particular). */
    private String detail;
  }
}
