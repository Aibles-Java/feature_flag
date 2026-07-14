package org.aibles.feature_flag.notification;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.client.RestClient;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SlackNotifierTest {

  @Mock RestClient restClient;

  @Test
  void send_doesNothing_whenDisabled() {
    SlackProperties properties = new SlackProperties();
    properties.setEnabled(false);
    properties.setWebhookUrl("https://hooks.slack.example/abc");
    SlackNotifier notifier = new SlackNotifier(properties, restClient);

    notifier.send("hello");

    verifyNoInteractions(restClient);
  }

  @Test
  void send_doesNothing_whenWebhookBlank() {
    SlackProperties properties = new SlackProperties();
    properties.setEnabled(true);
    properties.setWebhookUrl("  ");
    SlackNotifier notifier = new SlackNotifier(properties, restClient);

    notifier.send("hello");

    verifyNoInteractions(restClient);
  }

  @Test
  void send_postsToWebhook_whenEnabled() {
    SlackProperties properties = new SlackProperties();
    properties.setEnabled(true);
    properties.setWebhookUrl("https://hooks.slack.example/abc");

    RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
    RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
    RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
    when(restClient.post()).thenReturn(uriSpec);
    when(uriSpec.uri(any(String.class))).thenReturn(bodySpec);
    when(bodySpec.contentType(any())).thenReturn(bodySpec);
    when(bodySpec.body(any(Object.class))).thenReturn(bodySpec);
    when(bodySpec.retrieve()).thenReturn(responseSpec);

    SlackNotifier notifier = new SlackNotifier(properties, restClient);
    notifier.send("hello");

    verify(restClient).post();
    verify(uriSpec).uri("https://hooks.slack.example/abc");
    verify(bodySpec).body(java.util.Map.of("text", "hello"));
    verify(bodySpec).retrieve();
  }

  @Test
  void send_swallowsException_whenRestClientThrows() {
    SlackProperties properties = new SlackProperties();
    properties.setEnabled(true);
    properties.setWebhookUrl("https://hooks.slack.example/abc");
    when(restClient.post()).thenThrow(new RuntimeException("boom"));

    SlackNotifier notifier = new SlackNotifier(properties, restClient);

    assertThatCode(() -> notifier.send("hello")).doesNotThrowAnyException();
    verify(restClient).post();
    verify(restClient, never()).get();
  }
}
