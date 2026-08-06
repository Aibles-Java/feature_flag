package org.aibles.feature_flag.webhook;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Signs webhook payloads with HMAC-SHA256 so a receiver can prove a delivery came from us (issue
 * #36).
 *
 * <p>The signed value is {@code "<timestamp>.<body>"}, not the body alone. Including the timestamp
 * inside the signature is what makes {@code X-Webhook-Timestamp} trustworthy: if the timestamp were
 * only an unsigned header, an attacker replaying a captured request could rewrite it to "now" and
 * defeat the receiver's freshness check. Receivers should reject deliveries whose timestamp is
 * outside a tolerance window (a few minutes) <em>and</em> whose signature does not verify.
 */
@Component
public class WebhookSigner {

  public static final String SIGNATURE_HEADER = "X-Webhook-Signature";
  public static final String TIMESTAMP_HEADER = "X-Webhook-Timestamp";
  public static final String EVENT_HEADER = "X-Webhook-Event";

  /** Prefix identifying the scheme, so a future v2 can rotate without ambiguity. */
  private static final String PREFIX = "sha256=";

  private static final String ALGORITHM = "HmacSHA256";

  /**
   * @param secret the subscription's plaintext shared secret
   * @param timestamp epoch seconds, sent as {@code X-Webhook-Timestamp}
   * @param body the exact serialized JSON body that will be transmitted — sign the same bytes that
   *     go on the wire, never a re-serialization, or the receiver's digest will not match
   * @return {@code sha256=<hex>}
   */
  public String sign(String secret, long timestamp, String body) {
    byte[] mac = hmac(secret, timestamp + "." + body);
    return PREFIX + HexFormat.of().formatHex(mac);
  }

  /**
   * Verifies a signature. Provided so tests (and any future inbound-webhook support) exercise the
   * same code path a receiver would implement.
   *
   * <p>Uses {@link MessageDigest#isEqual} rather than {@code String.equals}: it is constant-time,
   * so an attacker cannot recover a valid signature byte-by-byte by timing how quickly comparison
   * fails.
   */
  public boolean verify(String secret, long timestamp, String body, String signature) {
    if (signature == null) {
      return false;
    }
    byte[] expected = sign(secret, timestamp, body).getBytes(StandardCharsets.UTF_8);
    return MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.UTF_8));
  }

  private static byte[] hmac(String secret, String payload) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
      return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException e) {
      // HmacSHA256 is mandatory on every JVM; an empty key is the only realistic failure.
      throw new IllegalStateException("Failed to sign webhook payload", e);
    }
  }
}
