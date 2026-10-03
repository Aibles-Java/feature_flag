# Handoff

*Ephemeral — overwritten by `/save-memory` at the end of each session. Read this first.*

## Current WIP

Feature `client-sdk` — building a **Feature Flag Client SDK** for external backend apps, WITHOUT
modifying this app's server code. Work is on a **git worktree** at
`.claude/worktrees/agent-a7cb0859c34b7699c`, branch **`feature/client-sdk`** (renamed from the
worktree branch), 5 commits ahead of `develop`:

- `538bb83` scaffold + TLS enforcement tests (G1 evidence)
- `93864df` resolve 1st code-review CRIT/HIGH; SDK tests gate CI
- `5522fa1` full SDK impl (cache, retry, coercion, diagnostics, FlagClient facade)
- `461abcc` resolve merged 2nd-round code+security review (5 HIGH + should-fixes)
- `9d4c9d1` cap fetchOne response body before parse (HF-4 completion)

State: **136 tests green, coverage ~86%, Spotless clean, BUILD SUCCESS**, verified independently.
Only `feature-flag-sdk/` + `.github/workflows/workflow.yml` changed — NO app code touched.
Module is standalone (build: `./mvnw -f feature-flag-sdk/pom.xml verify`).

SDK has passed 2 full independent review rounds (code + security, SoD) + 1 final re-review; all
CRITICAL/HIGH closed. `feature-flag-sdk/README.md` has the integration guide.

## Immediate next step: OPEN THE PR

The user approved pushing + opening the PR. Branch is already renamed to `feature/client-sdk`.
Steps:
1. This `/save-memory` run satisfies the pre-push memory gate (memory files change alongside code).
   Commit the memory changes (they live in the MAIN checkout — the gate checks the pushing repo).
2. Push `feature/client-sdk` to origin.
3. Open PR into `develop` using the `create-pr` skill (repo's 6-section format). Reviewer ≠ author
   (SoD — a human reviews/merges; AI does not self-merge).

## Context to load first

- `decisions/0036-client-sdk-standalone-module.md` — the whole SDK build + contracts.
- `conventions/sdk-must-match-live-server-contract.md` — why identifier is a query param; build cmd.
- `docs/sdk/` — PRD, HLD, LLD, 7 ADRs, OpenAPI, threat-model, security-review, walkthrough notes.
- `.chapter-forge/sdlc-state.json` + `.chapter-forge/memory/episodic/gate-log.jsonl` — SDLC state
  and the full review→fix loop history (iterations 1–8).

## After the PR — Gate G1 (still pending, HUMAN authority)

SDK code is clean, but Gate G1 (design) still needs human sign-off. Remaining G1 blockers are all
human/cross-team (not Maker-closeable):
- Risk/Compliance sign-off (data-residency / AI-tooling).
- Architect architecture-review record + sign-off; Security to lift conditions + approve ADRs
  (still `PROPOSED`).
- Server-side ticket: `EvaluationController` to accept `X-Flag-Identifier` header (deferred v2).

## Planned later-phase update

Move `identifier` from query param → `X-Flag-Identifier` header (needs coordinated server change).
Tracked in README "Known deviation" and decision 0036.
