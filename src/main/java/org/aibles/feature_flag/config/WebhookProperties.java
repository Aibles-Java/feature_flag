package org.aibles.feature_flag.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Outbound webhook configuration, bound from {@code app.webhook.*} (issue #36).
 *
 * <p>The encryption key is validated at startup with the same fail-fast rules as {@link
 * JwtProperties} — a webhook secret encrypted under a weak or placeholder key is not meaningfully
 * protected.
 *
 * <p><strong>Operational warning:</strong> {@code app.webhook.encryption-key} cannot be rotated in
 * place. Changing it makes every existing {@code secret_ciphertext} fail to decrypt, so each
 * subscription must be re-created with a fresh secret. Recorded in ADR-0005.
 */
@ConfigurationProperties(prefix = "app.webhook")
@Validated
public record WebhookProperties(
    boolean enabled,
    @NotBlank(
            message =
                "app.webhook.encryption-key is required — set the APP_WEBHOOK_ENCRYPTION_KEY"
                    + " environment variable")
        String encryptionKey,
    @Min(value = 1, message = "app.webhook.max-attempts must be at least 1")
        @Max(value = 10, message = "app.webhook.max-attempts must not exceed 10")
        int maxAttempts,
    Duration initialBackoff,
    Duration connectTimeout,
    Duration readTimeout,
    boolean allowPrivateAddresses) {

  /** Matches {@link JwtProperties}: 64 bytes of key material, i.e. `openssl rand -hex 64`. */
  private static final int MIN_KEY_BYTES = 64;

  private static final Set<String> PLACEHOLDER_MARKERS =
      Set.of("change-me", "changeme", "your-secret", "placeholder", "password", "example");

  private static final int MIN_DISTINCT_CHARS = 10;

  /** Defaults applied when a property is absent, so only the encryption key is mandatory. */
  public WebhookProperties {
    maxAttempts = maxAttempts == 0 ? 3 : maxAttempts;
    initialBackoff = initialBackoff == null ? Duration.ofSeconds(1) : initialBackoff;
    connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
    readTimeout = readTimeout == null ? Duration.ofSeconds(5) : readTimeout;
  }

  @AssertTrue(
      message =
          "app.webhook.encryption-key is an unresolved ${...} placeholder — "
              + "the APP_WEBHOOK_ENCRYPTION_KEY environment variable is not set")
  public boolean isEncryptionKeyResolved() {
    // The binder passes unresolvable ${VAR} placeholders through as literals rather than
    // throwing — see conventions/springboot-configprops-binding-gotchas.md.
    return encryptionKey == null || !encryptionKey.startsWith("${");
  }

  @AssertTrue(
      message =
          "app.webhook.encryption-key must be at least 64 bytes (512 bits) when UTF-8 encoded — "
              + "generate one with: openssl rand -hex 64")
  public boolean isEncryptionKeyLongEnough() {
    // null/blank is reported by @NotBlank and an unresolved placeholder by
    // isEncryptionKeyResolved; don't double-report those cases here.
    return encryptionKey == null
        || encryptionKey.startsWith("${")
        || encryptionKey.getBytes(StandardCharsets.UTF_8).length >= MIN_KEY_BYTES;
  }

  @AssertTrue(
      message =
          "app.webhook.encryption-key is a placeholder value — "
              + "set APP_WEBHOOK_ENCRYPTION_KEY to a real secret")
  public boolean isEncryptionKeyNotPlaceholder() {
    if (encryptionKey == null) {
      return true;
    }
    String lower = encryptionKey.toLowerCase(Locale.ROOT);
    return PLACEHOLDER_MARKERS.stream().noneMatch(lower::contains);
  }

  @AssertTrue(
      message =
          "app.webhook.encryption-key looks trivially low-entropy (too few distinct characters) — "
              + "generate a random key with: openssl rand -hex 64")
  public boolean isEncryptionKeyRandomEnough() {
    return encryptionKey == null
        || encryptionKey.startsWith("${")
        || encryptionKey.chars().distinct().count() >= MIN_DISTINCT_CHARS;
  }

  /** Records print every component by default; never expose the encryption key. */
  @Override
  public String toString() {
    return "WebhookProperties[enabled="
        + enabled
        + ", encryptionKey=***, maxAttempts="
        + maxAttempts
        + ", initialBackoff="
        + initialBackoff
        + ", connectTimeout="
        + connectTimeout
        + ", readTimeout="
        + readTimeout
        + ", allowPrivateAddresses="
        + allowPrivateAddresses
        + "]";
  }
}
