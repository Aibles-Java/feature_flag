package org.aibles.feature_flag.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;
import org.aibles.feature_flag.domain.enums.FlagValueType;
import org.aibles.feature_flag.domain.enums.ImportConflictStrategy;

/**
 * Body of {@code POST /api/v1/environments/{id}/import} (issue #38).
 *
 * <p>{@code snapshot} accepts an export envelope verbatim — the extra envelope fields an export
 * carries ({@code exportedAt}, {@code environmentId}, …) are ignored rather than rejected, so a
 * caller can pipe {@code GET .../export} straight back in.
 */
@Data
public class ImportEnvironmentRequest {

  /** When true the change set is computed and reported but nothing is written. */
  private boolean dryRun;

  /** Defaults to the non-destructive strategy so an unspecified import can never clobber state. */
  @NotNull private ImportConflictStrategy conflictStrategy = ImportConflictStrategy.SKIP;

  @NotNull @Valid private Snapshot snapshot;

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Snapshot {
    /** Validated against the supported version in the service, which 400s on a mismatch. */
    @NotNull private Integer schemaVersion;

    /**
     * Bounded because an import walks every entry with per-flag queries — an unbounded list is an
     * easy way to tie up a connection. 2000 is far above any realistic project's flag count.
     */
    @NotNull
    @Size(max = 2000)
    private List<@Valid FlagEntry> flags;
  }

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class FlagEntry {
    @NotBlank
    @Size(max = 255)
    private String key;

    @Size(max = 255)
    private String name;

    private String description;

    @NotNull private FlagValueType valueType;

    /** Boxed so a missing field is distinguishable from {@code false}; null is treated as false. */
    private Boolean archived;

    private Boolean enabled;

    private String value;

    @Min(0)
    @Max(100)
    private Integer rolloutPercent;
  }
}
