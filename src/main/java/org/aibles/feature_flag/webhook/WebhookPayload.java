package org.aibles.feature_flag.webhook;

import java.util.Map;
import org.aibles.feature_flag.domain.enums.WebhookEventType;

/**
 * The JSON body of a webhook delivery.
 *
 * <p>{@code data} is an open map rather than a typed field per event so adding an event type does
 * not change the envelope — receivers can switch on {@code event} and read the keys they know.
 *
 * @param event the event type, also sent as the {@code X-Webhook-Event} header
 * @param occurredAt epoch seconds when the delivery was assembled
 * @param environmentId the environment this delivery is scoped to; for project-scoped events this
 *     is the environment being fanned out to, so a receiver always knows which environment it is
 *     being told about
 * @param data event-specific fields — never a secret (no API keys, no webhook secrets)
 */
public record WebhookPayload(
    WebhookEventType event, long occurredAt, String environmentId, Map<String, Object> data) {}
