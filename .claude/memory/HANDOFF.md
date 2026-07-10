# Handoff

*Ephemeral — overwritten by `/save-memory` at the end of each session. Read this first.*

## Current WIP

**Harness-improvement session** (no GitHub issue) on branch `develop`. Added four
enforcement gaps — see `decisions/0012-harness-guards-spotless-coverage.md`. All logic
tested (guard block/allow, regex, JSON/XML validity); nothing committed yet.

Files touched this session:
- **New hooks:** `.claude/hooks/liquibase-immutable-guard.sh` (PreToolUse:Edit|Write|MultiEdit),
  `.claude/hooks/security-review-gate.sh` (Stop), `.claude/hooks/format-changed.sh` (Stop).
- **New script:** `.claude/scripts/coverage-floor.sh`.
- **`.claude/settings.json`** — wired the three new hooks in.
- **`pom.xml`** — added Spotless plugin (`spotless:check` bound to `verify`) + properties.
- **`CLAUDE.md`** — updated Code-style line; added Security-review + Migrations-immutable bullets.
- Plus this memory commit (decision 0012, MEMORY.md, HANDOFF, session log).

## Context to Load

- `decisions/0012-harness-guards-spotless-coverage.md` — what each hook/script does + the
  two required rollout commands.

## Status — DONE this session
- Installed JDK 21 (`brew install --cask temurin@21`, 21.0.11).
- `./mvnw spotless:apply` reformatted 103 files; `./mvnw verify` green (165 tests,
  spotless:check + jacoco:check pass).
- Coverage measured 84.8% → floor locked at 0.83 in `pom.xml`.
- Two commits built on `feature/harness-guards`: `style:` (103 java files) + `chore(harness):`.

## Next steps
1. **Push** `feature/harness-guards` and open a PR into `develop` (gitflow). Memory gate is
   satisfied (commit updates `.claude/memory/`).
2. Sanity-check the new hooks fire live: edit an existing migration → should block;
   touch `security/` then Stop → should nudge.
3. Unrelated `docs/ARCHITECTURE.md` change is still uncommitted — land or discard separately.

**Parked / cross-branch (from prior sessions):**
- **#25** actuator — PR #42 open, watch CI → merge; `issue-board.sh ready 25`.
- **#26** rate limiting — PR #41 open on `feature/issue-26-rate-limiting`.
- **#24** hash SDK keys — MERGED (PR #40).
- Issue #10 (`feature/issue-10-jwt-deleted-user-500`) — commit/push/PR/`ready 10` pending.
- Issue #17 (`feature/issue-17-estimate-issue-skill`) — commit + push + PR + `ready 17`.
- Uncommitted `docs/ARCHITECTURE.md` — unrelated; land or discard separately.
- Issue #14 (SonarQube) waiting on infra, holds `decisions/0006-*`.

**Follow-ups:**
- Superseded "Raise jacoco.line.coverage above 0.00" → now actionable via `coverage-floor.sh`.
- **#25:** reconsider Dockerfile HEALTHCHECK `readiness` → `liveness`; add DB-down readiness→503 test.
- **#26:** per-IP SDK limit for invalid keys; Redis backend for multi-instance.
- **#24:** make `feature_flags.key` H2-safe so SDK eval can be tested for a real 200.
