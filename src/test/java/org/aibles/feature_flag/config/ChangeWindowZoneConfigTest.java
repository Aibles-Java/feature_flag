package org.aibles.feature_flag.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.aibles.feature_flag.domain.entity.EnvironmentApiKey;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * S-2.12 (D-09): the {@code Clock} bean's zone comes from {@code app.change-window.zone} and a
 * missing/invalid value aborts startup instead of silently using the JVM zone.
 */
class ChangeWindowZoneConfigTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(Cfg.class);

  @Configuration
  @EnableConfigurationProperties(ChangeWindowProperties.class)
  @Import(AppConfig.class)
  static class Cfg {}

  @Test
  void clockBeanUsesTheConfiguredZone() {
    runner
        .withPropertyValues("app.change-window.zone=Asia/Ho_Chi_Minh")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              Clock clock = context.getBean(AppConfig.CHANGE_WINDOW_CLOCK, Clock.class);
              assertThat(clock.getZone()).isEqualTo(ZoneId.of("Asia/Ho_Chi_Minh"));
              // Not the JVM zone's view of the instant: 17:30 local at 10:30Z.
              assertThat(Instant.parse("2026-07-02T10:30:00Z").atZone(clock.getZone()).getHour())
                  .isEqualTo(17);
            });
  }

  @Test
  void generalClockStaysOnTheJvmZoneSoApiKeyExpiryIsUnaffected() {
    // JVM default zone differs from the configured change-window zone on purpose.
    ZoneId configured =
        ZoneId.systemDefault().equals(ZoneId.of("Pacific/Kiritimati"))
            ? ZoneId.of("Pacific/Pago_Pago")
            : ZoneId.of("Pacific/Kiritimati");
    runner
        .withPropertyValues("app.change-window.zone=" + configured.getId())
        .run(
            context -> {
              Clock general = context.getBean(Clock.class); // @Primary general clock
              assertThat(general.getZone()).isEqualTo(ZoneId.systemDefault());
              assertThat(context.getBean(AppConfig.CHANGE_WINDOW_CLOCK, Clock.class).getZone())
                  .isEqualTo(configured);
              // A key whose zone-less expiry was written in the JVM zone is judged in that zone.
              LocalDateTime now = LocalDateTime.now(general);
              EnvironmentApiKey key =
                  EnvironmentApiKey.builder().expiresAt(now.plusMinutes(30)).build();
              assertThat(key.isExpired(general)).isFalse();
              EnvironmentApiKey past =
                  EnvironmentApiKey.builder().expiresAt(now.minusMinutes(30)).build();
              assertThat(past.isExpired(general)).isTrue();
            });
  }

  @Test
  void utcCanBeConfiguredExplicitly() {
    runner
        .withPropertyValues("app.change-window.zone=UTC")
        .run(
            c ->
                assertThat(c.getBean(AppConfig.CHANGE_WINDOW_CLOCK, Clock.class).getZone())
                    .isEqualTo(ZoneId.of("UTC")));
  }

  @Test
  void missingZoneFailsStartup() {
    runner.run(
        context -> {
          assertThat(context).hasFailed();
          assertThat(rootMessages(context.getStartupFailure())).contains("app.change-window.zone");
        });
  }

  @Test
  void blankZoneFailsStartup() {
    runner.withPropertyValues("app.change-window.zone=  ").run(c -> assertThat(c).hasFailed());
  }

  @Test
  void invalidZoneFailsStartupWithAClearMessage() {
    runner
        .withPropertyValues("app.change-window.zone=Not/AZone")
        .run(
            context -> {
              assertThat(context).hasFailed();
              String msgs = rootMessages(context.getStartupFailure());
              assertThat(msgs)
                  .contains("app.change-window.zone")
                  .contains("not a valid IANA zone id");
            });
  }

  @Test
  void unresolvedPlaceholderFailsStartupNamingTheEnvVar() {
    // The prod profile uses ${APP_CHANGE_WINDOW_ZONE} with no default; the binder passes an
    // unresolved placeholder through as a literal.
    runner
        .withPropertyValues("app.change-window.zone=${APP_CHANGE_WINDOW_ZONE}")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(rootMessages(context.getStartupFailure()))
                  .contains("APP_CHANGE_WINDOW_ZONE");
            });
  }

  @Test
  void offsetOnlyOrAbbreviationZonesAreRejected() {
    // "EST"/"PST" are ambiguous short IDs that ZoneId.of() rejects; keep that strictness.
    runner.withPropertyValues("app.change-window.zone=EST").run(c -> assertThat(c).hasFailed());
  }

  private static String rootMessages(Throwable t) {
    StringBuilder sb = new StringBuilder();
    for (Throwable c = t; c != null; c = c.getCause()) {
      sb.append(c.getMessage()).append('\n');
    }
    return sb.toString();
  }
}
