# Handoff

*Ephemeral — overwritten by `/save-memory` at the end of each session. Read this first.*

## Current WIP

**Issue #36** (webhooks for flag change events) on branch `feature/issue-36-webhooks`
(→ `develop`, cut off `develop` @ `04ac6bf`). Implemented; `./mvnw clean verify` green —
**320 tests, 0 failures** (develop has 243, so +77), Spotless clean, coverage met.

Two design calls were confirmed with the human before coding: **AES-GCM encryption at rest** with a
new `APP_WEBHOOK_ENCRYPTION_KEY`, and **fan-out to every environment in the project** for
project-scoped flag events. Both recorded in `decisions/0022` + ADR-0005.

What landed:

- **Migration 012** (3 changesets): `webhook_subscription`, `webhook_subscription_event_type`
  (`@ElementCollection`), `webhook_delivery_attempt`. All cascade from their parent — the opposite of
  `audit_log` (011), which has no FKs by design.
- **`util/SecretCipher`** — AES-256-GCM, reversible **on purpose** (see the warning below).
- **`webhook/`** — `SsrfGuard`, `WebhookSigner`, `WebhookSender` (retry + attempt logging),
  `WebhookDispatcher` (`@Async @TransactionalEventListener(AFTER_COMMIT)`), `WebhookPayload`,
  `WebhookUrlNotAllowedException` (→ 400 in `GlobalExceptionHandler`).
- **CRUD** — `WebhookSubscriptionController` at `/api/v1/webhooks` (+ `/secret/rotate`,
  `/deliveries`), service with `PermissionService` checks, one-time secret reveal like
  `EnvironmentSecretResponse`.
- **Events** — added ids to `FlagStateChangedEvent`/`ApiKeyRotatedEvent`/`FlagArchivedEvent`; new
  `FlagCreatedEvent`/`FlagUpdatedEvent` (create/update published nothing before). All 4 publish sites
  + `SlackEventListenerTest` updated.
- **Config** — `WebhookProperties` (JwtProperties-style fail-fast validation), `WebhookConfig`
  (timeout'd `RestClient`, self-contained `ObjectMapper`, `SecretCipher` bean).
- **Docs** — ADR-0005, README env-var row + ops note, `.env.example`, CLAUDE.md section.

## ⚠️ The one thing not to "fix"

**The webhook secret is encrypted, NOT hashed.** HMAC signing needs the plaintext on every delivery.
The repo's `ApiKeyHasher`/SHA-256 precedent (SDK keys, refresh tokens) points the wrong way — anyone
"aligning" webhook secrets with it breaks signing permanently. Said explicitly in `SecretCipher`'s
Javadoc, ADR-0005, CLAUDE.md and `decisions/0022`.

Second: `SsrfGuard` deliberately runs **twice** (subscribe + every delivery attempt). DNS is mutable;
removing the delivery-time check reopens rebinding.

## Context to Load

- `decisions/0022-webhooks-hmac-encrypted-secret-ssrf.md` — the contradiction, the crypto choice, the
  testing traps.
- `docs/adr/ADR-0005-webhook-delivery-and-secret-storage.md` — full rationale + accepted risks.

## Next steps

1. Commit + push `feature/issue-36-webhooks`; open PR with `create-pr` (`Closes #36`); then
   `.claude/scripts/issue-board.sh ready 36`.
2. In the PR, flag: (a) **new required prod env var** `APP_WEBHOOK_ENCRYPTION_KEY`, needed even when
   webhooks are disabled, and not rotatable; (b) the encrypted-not-hashed decision; (c) the accepted
   SSRF TOCTOU window; (d) `docs/adr/README.md` will need a trivial index merge with PR #61.
3. **`/security-review` is warranted** and has NOT been run — this touches crypto, an SSRF guard, and
   secret storage. CLAUDE.md's gate asks for it before committing to sensitive areas. Also
   `/review-pr`: the session config forbids me spawning agents unasked, so neither ran.

## Cross-branch / open PRs

- **#43** (issue #27, docker port/non-root) — MERGEABLE, CI green. Decision **0019**.
- **#58** (issue #31, audit log) — MERGEABLE, CI green. Migration **011**. Decision **0020**.
  Unanswered review comment "check the warning please": every CI warning is pre-existing on develop
  (verified against run `30373689296`); only `HHH90000025 H2Dialect` is worth fixing.
- **#60** (issue #34, GHCR + Trivy) — MERGEABLE, CI green. Decision **0018**. Raises the JaCoCo floor
  to **0.87**; verified #58 (0.9099) and develop+#60 (0.8938) both clear it.
- **#61** (issue #35, percentage rollout) — MERGEABLE, CI green. Decision **0021**, ADR-0004. Adds the
  ADR-0003 **and** ADR-0004 index rows, so `docs/adr/README.md` collides trivially with this branch.
- **#53** (issue #30, evaluation cache) — open. Its pre-rollout `FlagStateSnapshot` design satisfies
  #35's caching bullet; ADR-0004 records the invariant.
- Migrations: develop at 010 · **011 = #58** · **012 = #36 (this branch)**.
- Decisions: **0018** #60 · **0019** #43 · **0020** #58 · **0021** #61 · **0022** #36 — collision-free
  in any merge order.

## Known landmines

- **Windows docs case-collision** (`docs/ARCHITECTURE.md` vs `docs/architecture.md`): while both paths
  are tracked the phantom one is *always* dirty and **`git merge` refuses to start**; `git stash` only
  flips which name is dirty. Fix is `git rm --cached docs/ARCHITECTURE.md`. develop already renamed
  the uppercase file to `docs/architecture-design-v1.md`; PRs #43 and #58 each carry the fix. Branches
  cut off current develop (#61, #36) never had the phantom.
- `./mvnw test -Dtest='A+B'` is invalid surefire syntax — use `-Dtest='A,B'`.
- `WebhookProperties` is a record: accessors have **no** `is` prefix.
