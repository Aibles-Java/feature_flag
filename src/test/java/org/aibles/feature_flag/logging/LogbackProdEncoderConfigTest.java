package org.aibles.feature_flag.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

/**
 * Guards the prod logging wiring (issue #28) without booting the app: the JSON encoder is only
 * referenced from {@code logback-spring.xml}'s {@code <springProfile name="prod">} block, so a typo
 * in the encoder class name or a missing dependency would otherwise stay hidden until a prod
 * deploy.
 *
 * <p>Checks the two failure modes cheaply: (1) the {@code logstash-logback-encoder} class named in
 * the config actually resolves on the classpath, and (2) {@code logback-spring.xml} is well-formed
 * and wires that exact class into the prod profile.
 */
class LogbackProdEncoderConfigTest {

  private static final String ENCODER_CLASS = "net.logstash.logback.encoder.LogstashEncoder";

  @Test
  void logstashEncoderClassIsOnClasspath() {
    assertThatCode(() -> Class.forName(ENCODER_CLASS)).doesNotThrowAnyException();
  }

  @Test
  void logbackSpringXmlIsWellFormedAndWiresJsonEncoderForProd() throws Exception {
    String xml =
        new String(
            getClass().getResourceAsStream("/logback-spring.xml").readAllBytes(),
            StandardCharsets.UTF_8);

    // Parses as XML (guards against a malformed config file).
    Document doc =
        javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(new InputSource(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))));
    assertThat(doc.getDocumentElement().getTagName()).isEqualTo("configuration");

    // The prod profile references the exact encoder class we verified above.
    assertThat(xml).contains("<springProfile name=\"prod\">").contains(ENCODER_CLASS);
  }
}
