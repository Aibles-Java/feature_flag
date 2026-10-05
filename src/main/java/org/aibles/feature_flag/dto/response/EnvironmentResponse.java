package org.aibles.feature_flag.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;
import org.aibles.feature_flag.domain.enums.EnvType;

@Data
@Builder
public class EnvironmentResponse {
  private UUID id;
  private String name;
  private String description;
  private UUID projectId;
  private EnvType type;
  private Integer changeWindowStartHour;
  private Integer changeWindowEndHour;
  private String changeWindowTimezone;

  /**
   * IANA zone the window is actually evaluated in: this environment's own {@code
   * changeWindowTimezone} if valid, else the configured change-window zone. Informational.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  private String changeWindowZone;

  /**
   * Whether a production change would pass the change window right now, exactly as {@code
   * PermissionService.withinChangeWindow} decides (an environment with no window, or start == end,
   * is unrestricted and reports {@code true}). Informational snapshot: the server re-checks on
   * every write. Null in audit snapshots (time dependent) and then omitted from the JSON. It
   * reflects the window only and ignores the environment type: the UI must not read it as
   * "production is open".
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  private Boolean changeWindowOpenNow;

  private LocalDateTime createdAt;
}
