package org.aibles.feature_flag.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import java.time.DateTimeException;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Production change-window configuration, bound from {@code app.change-window.*} (env: {@code
 * APP_CHANGE_WINDOW_ZONE}).
 *
 * <p>{@code zone} is the IANA zone in which the application {@link java.time.Clock} reads the hour
 * for {@code PermissionService.withinChangeWindow} whenever an environment has no timezone of its
 * own (D-09). It is validated at startup so a missing or invalid value aborts boot with a clear
 * message instead of silently using the JVM's default zone. The value must be confirmed by Ops
 * before deploy.
 */
@ConfigurationProperties(prefix = "app.change-window")
@Validated
public record ChangeWindowProperties(
    @NotBlank(
            message =
                "app.change-window.zone is required — set the APP_CHANGE_WINDOW_ZONE environment"
                    + " variable to an IANA zone id, e.g. Asia/Ho_Chi_Minh")
        String zone) {

  @AssertTrue(
      message =
          "app.change-window.zone is an unresolved ${...} placeholder — "
              + "the APP_CHANGE_WINDOW_ZONE environment variable is not set")
  public boolean isZoneResolved() {
    // The binder passes unresolvable ${VAR} placeholders through as literals.
    return zone == null || !zone.startsWith("${");
  }

  @AssertTrue(
      message =
          "app.change-window.zone is not a valid IANA zone id (e.g. Asia/Ho_Chi_Minh, UTC) — "
              + "check app.change-window.zone / APP_CHANGE_WINDOW_ZONE")
  public boolean isZoneValid() {
    if (zone == null || zone.isBlank() || zone.startsWith("${")) {
      return true; // reported by the other constraints; do not double-report
    }
    try {
      ZoneId.of(zone.trim());
      return true;
    } catch (DateTimeException e) {
      return false;
    }
  }

  /** The configured zone; only call on a validated instance. */
  public ZoneId zoneId() {
    return ZoneId.of(zone.trim());
  }
}
