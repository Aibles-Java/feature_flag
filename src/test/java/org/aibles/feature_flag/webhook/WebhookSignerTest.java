package org.aibles.feature_flag.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Issue #36 AC: "webhook signatures are verifiable using the shared secret." */
class WebhookSignerTest {

  private static final String SECRET = "whsec_test_secret_value";
  private static final String BODY = "{\"event\":\"FLAG_STATE_CHANGED\"}";
  private static final long TS = 1_754_000_000L;

  private final WebhookSigner signer = new WebhookSigner();

  @Test
  void isDeterministic() {
    assertThat(signer.sign(SECRET, TS, BODY)).isEqualTo(signer.sign(SECRET, TS, BODY));
  }

  @Test
  void verifiesItsOwnSignature() {
    String signature = signer.sign(SECRET, TS, BODY);

    assertThat(signer.verify(SECRET, TS, BODY, signature)).isTrue();
  }

  /**
   * The independent check that matters: a receiver implementing HMAC-SHA256 over "timestamp.body"
   * from the docs must arrive at the same value. Computed here from scratch rather than by calling
   * the signer, so this would catch a change to the signed string.
   */
  @Test
  @DisplayName("matches an independently computed HMAC-SHA256 over \"<timestamp>.<body>\"")
  void matchesAnIndependentlyComputedHmac() throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    String expected =
        "sha256="
            + HexFormat.of()
                .formatHex(mac.doFinal((TS + "." + BODY).getBytes(StandardCharsets.UTF_8)));

    assertThat(signer.sign(SECRET, TS, BODY)).isEqualTo(expected);
  }

  @Test
  void rejectsWrongSecret() {
    String signature = signer.sign(SECRET, TS, BODY);

    assertThat(signer.verify("whsec_other", TS, BODY, signature)).isFalse();
  }

  /**
   * The timestamp is inside the signed string precisely so this fails: if it were only an unsigned
   * header, a replayed request could be re-stamped with "now" and still verify.
   */
  @Test
  @DisplayName("a rewritten timestamp invalidates the signature (replay defence)")
  void rejectsRewrittenTimestamp() {
    String signature = signer.sign(SECRET, TS, BODY);

    assertThat(signer.verify(SECRET, TS + 1, BODY, signature)).isFalse();
  }

  @Test
  void rejectsTamperedBody() {
    String signature = signer.sign(SECRET, TS, BODY);

    assertThat(signer.verify(SECRET, TS, BODY + " ", signature)).isFalse();
  }

  @Test
  void rejectsNullAndGarbageSignatures() {
    assertThat(signer.verify(SECRET, TS, BODY, null)).isFalse();
    assertThat(signer.verify(SECRET, TS, BODY, "")).isFalse();
    assertThat(signer.verify(SECRET, TS, BODY, "sha256=deadbeef")).isFalse();
  }

  @Test
  @DisplayName("signature is prefixed and hex-encoded, so receivers can parse the scheme")
  void hasStableFormat() {
    assertThat(signer.sign(SECRET, TS, BODY)).matches("sha256=[0-9a-f]{64}");
  }

  @Test
  void differentBodiesProduceDifferentSignatures() {
    assertThat(signer.sign(SECRET, TS, "{\"a\":1}"))
        .isNotEqualTo(signer.sign(SECRET, TS, "{\"a\":2}"));
  }
}
