# ADR-0005: Webhook Delivery, Signing, and Secret Storage

**Status:** Accepted
**Date:** 2026-08-06

## Context

Issue #36 adds outbound webhooks so external systems (Slack, CI, audit pipelines) learn about flag
changes without polling. Subscribers need to trust that a delivery really came from this platform,
which means signing each request with a per-subscription shared secret.

The issue's acceptance criteria ask for signatures "verifiable using the shared secret" and for
secrets to be "**hashed**/encrypted when stored". Those two requirements are in direct conflict, and
resolving it is the central decision here — this repo already hashes two other secret kinds
(`ApiKeyHasher` for SDK keys in migration 009, refresh tokens in 010), so "follow the precedent"
would be the natural but wrong move.

## Decision

### 1. The shared secret is encrypted, never hashed

**HMAC signing requires the plaintext secret on every delivery.** A hash is one-way, so hashing a
webhook secret would make signing permanently impossible. The AC's "hashed" option is not
implementable, not merely weaker.

So `webhook_subscription.secret_ciphertext` holds the secret under **AES-256-GCM**
(`util/SecretCipher`), keyed from `app.webhook.encryption-key`:

- **GCM, not CBC/ECB** — authenticated encryption. A wrong key or tampered ciphertext fails loudly
  on decrypt instead of yielding a silently wrong signing key, which would produce deliveries no
  subscriber could verify and no obvious cause.
- **A fresh random 96-bit IV per encryption**, prepended to the ciphertext. IV reuse under one key
  is catastrophic for GCM, so this is not optional; it also means encrypting the same secret twice
  yields different ciphertext (asserted in `SecretCipherTest`).
- **Key derived by SHA-256** over the configured value, folding any length to the 32 bytes AES-256
  needs. Not a password KDF, for the same reason `ApiKeyHasher` skips one: the configured value is
  high-entropy random material, validated for length/entropy at startup, not a human password.

This is a **deliberate downgrade** from hashing, forced by the feature. It is why the key gets the
same fail-fast validation as the JWT secret, and why `SecretCipher`'s Javadoc tells future readers
not to "improve" it into a hash.

**Consequence: the encryption key cannot be rotated in place.** Changing it orphans every stored
secret. Documented in README, `application-prod.properties`, and `WebhookProperties`.

Plaintext is revealed **once**, on create and on rotate, via `WebhookSubscriptionSecretResponse` —
the same one-time-reveal shape `EnvironmentSecretResponse` uses for API keys. Read endpoints never
return it, in either form.

### 2. Sign `"<timestamp>.<body>"`, not the body alone

`X-Webhook-Signature: sha256=<hex>` is HMAC-SHA256 over `timestamp + "." + body`, with the timestamp
also sent as `X-Webhook-Timestamp`.

The timestamp is *inside* the signed string on purpose. If it were only an unsigned header, an
attacker replaying a captured delivery could rewrite it to "now" and defeat the receiver's freshness
check. Receivers should reject deliveries outside a tolerance window **and** with a bad signature.

Two supporting details: the signature covers the exact serialized bytes that go on the wire (the
sender passes the pre-serialized `String`, never re-serializing), and `verify` uses
`MessageDigest.isEqual` so comparison is constant-time.

### 3. Retry only what retrying can fix, and give the receiver an idempotency key

**Not every failure is worth a retry.** A 4xx means the request itself is wrong, so replaying it
unchanged fails identically — three attempts would burn the budget and delay nothing. Only 5xx (the
server's problem, may clear), 408 and 429 (which explicitly invite a retry), and connection/DNS
errors are retried. An SSRF rejection is permanent by definition. `WebhookSender` models this as
`SUCCESS` / `RETRYABLE` / `PERMANENT` rather than a boolean, so the distinction is visible.

**Every delivery carries a `deliveryId`**, in the signed body and as `X-Webhook-Delivery`, unique per
delivery and **stable across its retries**. This is the receiver's idempotency key. Without it, a
delivery that the subscriber processed but whose response was lost is retried and is
*indistinguishable* from a genuinely new event — the subscriber double-processes with no way to
detect it. Retries re-sign with a fresh timestamp (so a retry never falls outside the receiver's
freshness window) while the id stays fixed.

### 3b. Retry mechanics: in-process, 3 attempts, doubling backoff, every attempt persisted

Delivery runs on the existing `@Async @TransactionalEventListener(AFTER_COMMIT)` pipeline that
`SlackEventListener` already uses — after-commit so a rolled-back mutation notifies nobody, async so
subscriber latency never delays the admin request.

Retries are a plain loop with `Thread.sleep`, not `spring-retry`: the async thread's only job is this
delivery, so blocking it is the intended behaviour, and it avoids a new dependency (mirroring issue
#30's choice of raw Caffeine over the Spring Cache abstraction). Each attempt is written to
`webhook_delivery_attempt` **before** the next one, so history survives a crash mid-retry.

The delivery client has explicit connect/read timeouts. Without them a subscriber that accepts a
connection and never answers would pin an async thread forever, and enough such endpoints would
exhaust the executor and stall all notifications.

Recorded errors are the exception **class name only** — never `getMessage()`, which embeds the target
URL, and a webhook URL can itself carry a token. Same reasoning `SlackNotifier` documents.

### 4. SSRF guard, checked at subscribe **and** delivery time

A webhook URL is caller-controlled input that the server then requests. `SsrfGuard` rejects non-HTTP
schemes and any URL resolving to a loopback, link-local, site-local, wildcard, or multicast address —
notably `169.254.169.254`, the cloud metadata endpoint.

Checked twice deliberately: at subscribe time so the operator gets an immediate 400, and again on
**every delivery attempt**, because DNS is mutable. A hostname that resolved publicly at subscribe
time can later resolve to `127.0.0.1`, so a subscribe-time-only check is bypassable by design.

**Residual risk, accepted:** even the delivery-time check has a TOCTOU window — the HTTP client
re-resolves the hostname when it connects, so a name flipping between the check and the connection
could still be reached. Closing it fully means pinning the connection to the validated IP (a custom
`DnsResolver`/socket factory), which is out of scope here. Anyone hardening this later should start
at `WebhookSender.attemptDelivery`.

`app.webhook.allow-private-addresses` exists only so local dev and the integration test can POST to
`127.0.0.1`; it defaults to `false`.

**A redirect would bypass the guard entirely**, and the thing preventing that is not in our code. A
subscriber could register a public URL that `302`s to `http://169.254.169.254/`; the guard validates
the registered URL and cannot see the hop. Deliveries are safe today only because Spring's
`SimpleClientHttpRequestFactory` enables `setInstanceFollowRedirects` for `GET`, and deliveries are
`POST` — an implementation detail of the configured request factory. Swapping that factory (the JDK
and Apache clients follow redirects on POST for some statuses) would silently reopen the hole, so
`WebhookRedirectNotFollowedTest` pins the behaviour rather than leaving it to a comment.

### 5. Project-scoped events fan out to every environment

Subscriptions are per-environment, but a flag belongs to a project. Flag create/update/archive
therefore deliver to the subscriptions of **every** environment in the project — which matches the
data model, since creating a flag auto-creates one `FlagEnvironmentState` per environment. Flag-state
changes and API-key rotation already name one environment and go only there.

Every payload carries `environmentId`, so a receiver of a fanned-out event always knows which
environment it is being told about.

This required adding ids to three existing event records (`FlagStateChangedEvent`,
`ApiKeyRotatedEvent`, `FlagArchivedEvent`), which previously carried only display names — names
cannot identify which subscriptions to notify.

## Consequences

- **Good:** webhooks reuse a proven pipeline; no new infrastructure, no new dependency, and Slack and
  webhooks are independent (one failing cannot affect the other).
- **Good:** `webhook_delivery_attempt` makes a failing endpoint debuggable from the API
  (`GET /api/v1/webhooks/{id}/deliveries`) rather than only from logs.
- **Bad / accepted:** in-process retry means a process restart mid-backoff loses the remaining
  attempts. A durable outbox with a scheduled re-drive is the fix if at-least-once delivery is ever
  required; today's guarantee is best-effort with a recorded audit trail.
- **Bad / accepted:** retries occupy an async thread while sleeping. With the default 3 attempts and
  1s/2s backoff the worst case is a few seconds per delivery, but a large fan-out to many dead
  endpoints could saturate the executor. Bounded by the timeouts; worth a dedicated executor if
  subscription counts grow.
- **Operational sharp edge:** `APP_WEBHOOK_ENCRYPTION_KEY` is required in prod even with webhooks
  disabled (startup validation is unconditional), and losing or changing it orphans every stored
  secret.

## Alternatives Considered

- **Hash the secret, per the AC wording and the `ApiKeyHasher` precedent.** Impossible — signing
  needs the plaintext. Recorded here because the precedent makes it a likely future "fix".
- **Store the secret in plaintext.** Simpler, and what several webhook products do early on. Rejected:
  encryption is cheap here and a DB-only compromise (backup, read replica, SQL injection) should not
  hand over every subscriber's signing key.
- **Asymmetric signatures (Ed25519), publishing a public key.** Better — subscribers verify without
  holding a shared secret, and nothing sensitive is stored. Rejected for v1 because the AC explicitly
  specifies HMAC-SHA256 with a shared secret, and it needs key distribution/rotation machinery. The
  `sha256=` prefix on the signature leaves room to add a second scheme later.
- **`spring-retry` with `@Retryable`.** Rejected: a new dependency for a 15-line loop, and the
  declarative model fits poorly with persisting each attempt.
- **A durable outbox table + `@Scheduled` re-drive.** The right answer for at-least-once delivery.
  Deferred: it is a larger change than the issue scopes, and `webhook_delivery_attempt` already gives
  the visibility to justify it later with real data.
- **Denying only loopback, allowing RFC-1918.** Rejected: an internal service on `10.x` is exactly
  what an SSRF probe wants to reach.
