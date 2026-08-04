package org.aibles.feature_flag.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Startup validation for {@link JwtProperties}. Uses ApplicationContextRunner, which exercises the
 * same ConfigurationProperties binding + Bean Validation path as real application startup: an
 * invalid secret must abort context creation (fail fast).
 */
class JwtPropertiesValidationTest {

  /** The secret that used to be committed in application.properties — must be rejected. */
  private static final String COMMITTED_PLACEHOLDER =
      "change-me-to-a-very-long-secret-key-at-least-512-bits-long-in-production-env";

  private static final String VALID_SECRET =
      "a-valid-secret-for-tests-0123456789-0123456789-0123456789-0123456789";

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(JwtPropertiesConfig.class);

  @Configuration
  @EnableConfigurationProperties(JwtProperties.class)
  static class JwtPropertiesConfig {}

  @Test
  void validSecretBindsAndStarts() {
    runner
        .withPropertyValues(
            "app.jwt.secret=" + VALID_SECRET,
            "app.jwt.access-expiration-ms=86400000",
            "app.jwt.refresh-expiration-ms=1209600000")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              JwtProperties props = context.getBean(JwtProperties.class);
              assertThat(props.secret()).isEqualTo(VALID_SECRET);
              assertThat(props.accessExpirationMs()).isEqualTo(86400000L);
            });
  }

  @Test
  void missingSecretFailsStartup() {
    runner
        .withPropertyValues(
            "app.jwt.access-expiration-ms=86400000", "app.jwt.refresh-expiration-ms=1209600000")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(rootMessages(context.getStartupFailure())).contains("app.jwt");
            });
  }

  @Test
  void blankSecretFailsStartup() {
    runner
        .withPropertyValues(
            "app.jwt.secret=   ",
            "app.jwt.access-expiration-ms=86400000",
            "app.jwt.refresh-expiration-ms=1209600000")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void secretShorterThan512BitsFailsStartup() {
    String sixtyThreeBytes = "x".repeat(63);
    assertThat(sixtyThreeBytes.getBytes(StandardCharsets.UTF_8)).hasSize(63);

    runner
        .withPropertyValues(
            "app.jwt.secret=" + sixtyThreeBytes,
            "app.jwt.access-expiration-ms=86400000",
            "app.jwt.refresh-expiration-ms=1209600000")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(rootMessages(context.getStartupFailure())).contains("512 bits");
            });
  }

  @Test
  void multiByteSecretIsMeasuredInBytesNotChars() {
    // 40 chars × 2 UTF-8 bytes each = 80 bytes but only 40 chars (< 64): must be
    // accepted, proving the check counts encoded bytes rather than String length.
    // Ten distinct characters keep it above the low-entropy floor.
    String fortyTwoByteChars = "áéíóúàèìòù".repeat(4);
    assertThat(fortyTwoByteChars.getBytes(StandardCharsets.UTF_8)).hasSize(80);
    assertThat(fortyTwoByteChars).hasSizeLessThan(64);

    runner
        .withPropertyValues(
            "app.jwt.secret=" + fortyTwoByteChars,
            "app.jwt.access-expiration-ms=86400000",
            "app.jwt.refresh-expiration-ms=1209600000")
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void triviallyLowEntropySecretFailsStartupDespiteBeingLongEnough() {
    runner
        .withPropertyValues(
            "app.jwt.secret=" + "x".repeat(64),
            "app.jwt.access-expiration-ms=86400000",
            "app.jwt.refresh-expiration-ms=1209600000")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(rootMessages(context.getStartupFailure())).contains("low-entropy");
            });
  }

  @Test
  void wellKnownPlaceholderMarkersAreRejected() {
    String padding = "-0123456789abcdefghij".repeat(3);
    for (String marker : new String[] {"changeme", "your-secret", "password"}) {
      runner
          .withPropertyValues(
              "app.jwt.secret=" + marker + padding,
              "app.jwt.access-expiration-ms=86400000",
              "app.jwt.refresh-expiration-ms=1209600000")
          .run(context -> assertThat(context).hasFailed());
    }
  }

  @Test
  void committedPlaceholderSecretFailsStartupDespiteBeingLongEnough() {
    assertThat(COMMITTED_PLACEHOLDER.getBytes(StandardCharsets.UTF_8).length)
        .isGreaterThanOrEqualTo(64);

    runner
        .withPropertyValues(
            "app.jwt.secret=" + COMMITTED_PLACEHOLDER,
            "app.jwt.access-expiration-ms=86400000",
            "app.jwt.refresh-expiration-ms=1209600000")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(rootMessages(context.getStartupFailure())).contains("placeholder");
            });
  }

  @Test
  void unresolvedEnvVarPlaceholderFailsStartupNamingTheVariable() {
    // The binder leaves unresolvable ${VAR} references as literal text instead of
    // throwing; the validation must turn that into a message naming the env var.
    runner
        .withPropertyValues(
            "app.jwt.secret=${FEATURE_FLAG_TEST_UNSET_VAR}",
            "app.jwt.access-expiration-ms=86400000",
            "app.jwt.refresh-expiration-ms=1209600000")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(rootMessages(context.getStartupFailure()))
                  .contains("environment variable");
            });
  }

  @Test
  void placeholderCheckIsCaseInsensitive() {
    String shoutyPlaceholder = "CHANGE-ME-" + "x".repeat(64);

    runner
        .withPropertyValues(
            "app.jwt.secret=" + shoutyPlaceholder,
            "app.jwt.access-expiration-ms=86400000",
            "app.jwt.refresh-expiration-ms=1209600000")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void nonPositiveExpirationFailsStartup() {
    runner
        .withPropertyValues(
            "app.jwt.secret=" + VALID_SECRET,
            "app.jwt.access-expiration-ms=0",
            "app.jwt.refresh-expiration-ms=1209600000")
        .run(context -> assertThat(context).hasFailed());
  }

  /** Flattens the startup failure's cause chain into one searchable message string. */
  private static String rootMessages(Throwable failure) {
    StringBuilder sb = new StringBuilder();
    for (Throwable t = failure; t != null; t = t.getCause()) {
      sb.append(t.getMessage()).append('\n');
    }
    return sb.toString();
  }
}
