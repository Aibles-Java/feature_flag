---
name: 0022-webhooks-hmac-encrypted-secret-ssrf
description: issue #36 — the AC's "hashed" secret is impossible with HMAC signing, so AES-GCM encryption; SSRF guard must re-check at delivery time; project-scoped events fan out per environment
metadata:
  type: decision
---

# 0022 — Webhooks: encrypted (not hashed) secret, SSRF, fan-out (issue #36)

Full rationale in `docs/adr/ADR-0005-webhook-delivery-and-secret-storage.md`. This records the parts
that would otherwise be re-derived or "fixed" wrongly.

## The AC contradicted itself, and the wrong resolution is the tempting one

The issue asks for signatures "verifiable using the shared secret" **and** for secrets
"hashed/encrypted when stored". **Hashing is impossible**: HMAC needs the plaintext on every
delivery, and a hash is one-way.

This matters because the repo has two hashing precedents pointing the wrong way —
`ApiKeyHasher`/SHA-256 for SDK keys (migration 009) and refresh tokens (010). A future reader
"aligning" webhook secrets with them silently breaks signing forever. `SecretCipher`'s Javadoc,
ADR-0005, and CLAUDE.md all now say so explicitly.

Chosen: **AES-256-GCM**, fresh 96-bit IV per encryption (GCM + IV reuse = catastrophic; also means
same plaintext → different ciphertext, which the test asserts), key folded to 32 bytes via SHA-256 of
the configured value. GCM's tag makes a wrong key a loud failure instead of a silently wrong signing
key — which would otherwise produce unverifiable deliveries with no obvious cause.

**`APP_WEBHOOK_ENCRYPTION_KEY` is not rotatable in place** — changing it orphans every stored secret.
Required in the prod profile even when `app.webhook.enabled=false`, because bind-time validation is
unconditional.

## Sign `"<timestamp>.<body>"`, never the body alone

If the timestamp were only an unsigned header, a replayed delivery could be re-stamped with "now" and
still verify, defeating the receiver's freshness window. Putting it inside the HMAC is what makes
`X-Webhook-Timestamp` trustworthy. Also: sign the exact serialized bytes sent (pass the pre-serialized
`String` to the client, never re-serialize), and compare with `MessageDigest.isEqual` for
constant-time.

## SSRF: the second check is the one that matters

`SsrfGuard` runs at **subscribe time** (immediate 400 for the operator) *and* on **every delivery
attempt**. DNS is mutable — a hostname that resolved publicly at subscribe time can later resolve to
`127.0.0.1`, so a subscribe-time-only check is bypassable by rebinding. Do not optimise the
delivery-time check away.

Blocks loopback / link-local (`169.254.169.254` = cloud metadata) / site-local / wildcard / multicast,
and non-http(s) schemes. **Accepted residual risk:** the HTTP client re-resolves on connect, so a
TOCTOU window remains; closing it needs connection pinning to the validated IP.

## Environment- vs project-scoped events

Subscriptions are per-environment, but a flag belongs to a project. Flag create/update/archive fan out
to **every environment in the project** (mirrors the model: creating a flag auto-creates one
`FlagEnvironmentState` per environment). Flag-state change and key rotation are already
environment-scoped.

This forced **ids onto three existing event records** (`FlagStateChangedEvent`, `ApiKeyRotatedEvent`,
`FlagArchivedEvent`) — they carried only display names, which cannot identify subscriptions. Adding a
record component changes the constructor, so all 4 publish sites and `SlackEventListenerTest` had to
change too. Added `FlagCreatedEvent`/`FlagUpdatedEvent`; `create()`/`update()` published nothing
before.

## Testing notes worth reusing

- The integration test **must not be `@Transactional`** — the dispatcher listens on `AFTER_COMMIT`, so
  a rolled-back test transaction fires nothing and the test would pass while delivering zero requests.
  Publish via `TransactionTemplate` instead. (Same trap as [[0017-refresh-token-family-revoke-transaction-semantics]].)
- A JDK `com.sun.net.httpserver.HttpServer` on port 0 is enough for a real end-to-end HTTP assertion —
  no WireMock dependency. Needs `app.webhook.allow-private-addresses=true` since it binds loopback.
- `awaitility` 4.3.0 is already on the test classpath via `spring-boot-starter-test`.
- Distinct `@SpringBootTest(properties=...)` forks a context → give it its own H2 db name, per
  [[second-springboottest-context-shared-h2]].
- `WebhookProperties` is a **record**, so accessors have no `is` prefix — `allowPrivateAddresses()`,
  not `isAllowPrivateAddresses()`. Cost one compile cycle.

## Found on a second review pass (all fixed in the same PR)

1. **A redirect bypasses `SsrfGuard` completely** — a public URL can `302` to
   `169.254.169.254` and the guard never sees the hop. Safe today *only* because Spring's
   `SimpleClientHttpRequestFactory` sets `setInstanceFollowRedirects` for `GET` and deliveries are
   `POST`. Verified empirically, not assumed. That is a property of the configured request factory,
   invisible in our code, so `WebhookRedirectNotFollowedTest` pins it — swapping to the JDK/Apache
   client would otherwise silently reopen the hole.
2. **4xx was being retried.** Replaying an unchanged bad request fails identically, so 3 attempts
   burned the budget for nothing. Now `SUCCESS`/`RETRYABLE`/`PERMANENT`: retry 5xx, 408, 429 and
   connection errors; treat other 4xx and any SSRF rejection as permanent.
3. **No idempotency key.** A delivery the subscriber processed but whose response was lost gets
   retried and is indistinguishable from a new event → silent double-processing. Added `deliveryId`
   (signed body + `X-Webhook-Delivery`), fixed across retries while the timestamp/signature change
   per attempt.
4. **Convention violation:** the exception lived in `webhook/`, but every other exception in this repo
   is in `exception/` — and `GlobalExceptionHandler` importing a feature package is backwards. Moved.
5. **`EnumSet.copyOf(Collection)` throws on an empty non-EnumSet** → a direct service call with an
   empty event-type set was a 500. `@NotEmpty` covers the API path; the service now builds the set
   additively so it cannot 500.

Lesson worth keeping: the two real security findings were both about **what the code does not say** —
an implicit request-factory behaviour and a missing idempotency key. Neither is visible by reading the
guard itself.

## Numbering

Migration **012** (011 reserved by issue #31 / PR #58). Decision **0022** (0018 → PR #60, 0019 → #43,
0020 → #58, 0021 → #61).
