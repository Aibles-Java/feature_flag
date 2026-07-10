package org.aibles.feature_flag.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aibles.feature_flag.notification.event.ApiKeyRotatedEvent;
import org.aibles.feature_flag.notification.event.FlagArchivedEvent;
import org.aibles.feature_flag.notification.event.FlagStateChangedEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Consumes notification events after the surrounding transaction commits, on an async thread, and
 * forwards a formatted message to Slack. Runs after-commit so a rollback never emits a
 * notification, and async so Slack latency never delays the request.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SlackEventListener {

  private final SlackNotifier slackNotifier;

  @Async
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onFlagStateChanged(FlagStateChangedEvent event) {
    String severity = isProduction(event.environmentName()) ? "🔴 " : "🏳️ ";
    String message =
        String.format(
            "%s:triangular_flag_on_post: Flag `%s` in *%s* (%s) → enabled=%s, value=%s — by %s",
            severity,
            event.flagKey(),
            event.environmentName(),
            event.projectName(),
            event.newEnabled(),
            event.newValue(),
            event.actorEmail());
    slackNotifier.send(message);
  }

  @Async
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onApiKeyRotated(ApiKeyRotatedEvent event) {
    String severity = isProduction(event.environmentName()) ? "🔴 " : "ℹ️ ";
    String message =
        String.format(
            "%s:key: API key rotated for *%s* (%s) — by %s",
            severity, event.environmentName(), event.projectName(), event.actorEmail());
    slackNotifier.send(message);
  }

  @Async
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onFlagArchived(FlagArchivedEvent event) {
    String message =
        String.format(
            "ℹ️ :file_cabinet: Flag `%s` (%s) %s — by %s",
            event.flagKey(),
            event.projectName(),
            event.archived() ? "archived" : "unarchived",
            event.actorEmail());
    slackNotifier.send(message);
  }

  private static boolean isProduction(String environmentName) {
    return "production".equalsIgnoreCase(environmentName);
  }
}
