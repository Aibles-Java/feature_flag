package org.aibles.feature_flag.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * JWT signing configuration, bound from {@code app.jwt.*} (env: {@code APP_JWT_SECRET}, {@code
 * APP_JWT_EXPIRATION_MS}). Validated at startup so a misconfigured secret aborts boot with a clear
 * binding-failure report instead of running with a weak/known key.
 */
@ConfigurationProperties(prefix = "app.jwt")
@Validated
public record JwtProperties(
    @NotBlank(message = "app.jwt.secret is required — set the APP_JWT_SECRET environment variable")
        String secret,
    @Positive(message = "app.jwt.access-expiration-ms must be a positive number of milliseconds")
        long accessExpirationMs,
    @Positive(message = "app.jwt.refresh-expiration-ms must be a positive number of milliseconds")
        long refreshExpirationMs) {

  /** HS512 requires a key of at least 512 bits. */
  private static final int MIN_SECRET_BYTES = 64;

  /**
   * Substrings of well-known placeholder secrets, including the value this repo used to commit
   * ("change-me-..."). Must not match the intentional local-dev default in application.properties.
   */
  private static final Set<String> PLACEHOLDER_MARKERS =
      Set.of("change-me", "changeme", "your-secret", "placeholder", "password", "example");

  /**
   * Minimum distinct characters. A crude entropy proxy that rejects trivially repetitive keys
   * ("xxxx...") while always passing real random keys — hex output of `openssl rand -hex 64` draws
   * from 16 characters, so stay safely below that.
   */
  private static final int MIN_DISTINCT_CHARS = 10;

  @AssertTrue(
      message =
          "app.jwt.secret is an unresolved ${...} placeholder — "
              + "the APP_JWT_SECRET environment variable is not set")
  public boolean isSecretResolved() {
    // Spring Boot's binder passes unresolvable ${VAR} placeholders through as
    // literals instead of throwing, so a missing env var must be caught here.
    return secret == null || !secret.startsWith("${");
  }

  @AssertTrue(
      message =
          "app.jwt.secret must be at least 64 bytes (512 bits) when UTF-8 encoded — "
              + "generate one with: openssl rand -hex 64")
  public boolean isSecretLongEnough() {
    // null/blank is reported by @NotBlank and an unresolved placeholder by
    // isSecretResolved; don't double-report those cases here.
    return secret == null
        || secret.startsWith("${")
        || secret.getBytes(StandardCharsets.UTF_8).length >= MIN_SECRET_BYTES;
  }

  @AssertTrue(
      message = "app.jwt.secret is a placeholder value — set APP_JWT_SECRET to a real secret")
  public boolean isSecretNotPlaceholder() {
    if (secret == null) {
      return true;
    }
    String lower = secret.toLowerCase(Locale.ROOT);
    return PLACEHOLDER_MARKERS.stream().noneMatch(lower::contains);
  }

  @AssertTrue(
      message =
          "app.jwt.secret looks trivially low-entropy (too few distinct characters) — "
              + "generate a random key with: openssl rand -hex 64")
  public boolean isSecretRandomEnough() {
    return secret == null
        || secret.startsWith("${")
        || secret.chars().distinct().count() >= MIN_DISTINCT_CHARS;
  }

  /** Records print all components by default; never expose the signing key. */
  @Override
  public String toString() {
    return "JwtProperties[secret=***, accessExpirationMs="
        + accessExpirationMs
        + ", refreshExpirationMs="
        + refreshExpirationMs
        + "]";
  }
}
