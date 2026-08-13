# Handoff

*Ephemeral — overwritten by `/save-memory` at the end of each session. Read this first.*

## Current WIP

Two **stacked** branches, both ArchUnit governance, both green:

1. **Issue #48** — Tier 1 (R1–R7) on `feature/issue-48-archunit-governance`. **PR #65 open**, CI
   green. Includes a review round that closed two vacuous rules (R6 name filter, R4 dead
   `EvaluationController` carve-out) and hardened the freeze store
   (`allowStoreCreation=false`).
2. **Issue #49** — Tier 2 (R8/R9, immutable flag key) on `feature/issue-49-archunit-tier2`,
   **branched from #48's branch**, since Tier 2 adds rules to the file Tier 1 creates.
   `./mvnw verify`: 259 tests, `ArchitectureTest` 9/9, Spotless + JaCoCo green.

**Merge order matters: #65 first, then #49** (or rebase #49 onto `develop` once #65 lands).

## Context to Load

- `decisions/0023-archunit-tier2-immutable-flag-key.md` — why both of issue #49's stated conditions
  are vacuous under Lombok, and what was built instead.
- `decisions/0022-archunit-tier1-governance-gate.md` — Tier-1 gotchas, freeze-store hazards, and how
  to run a negative test without a false green.

## Unrelated bug found while running the FE — NOT yet filed

`POST /api/v1/auth/login` with wrong credentials returns **500, not 401**.
`AuthServiceImpl.login()` calls `authenticationManager.authenticate(...)`, which throws
`BadCredentialsException`; `GlobalExceptionHandler` has no handler for Spring Security's
`AuthenticationException`, so it falls through to `@ExceptionHandler(Exception.class)` → 500
"An unexpected error occurred". Pre-existing on `develop` (reproduced against the running backend
on :8081; confirmed by a real browser request from the FE origin).

Impact: users see a system error instead of "wrong password", and the FE's axios interceptor
(`src/api/axios.ts`) only handles `401`, so its token-clear/redirect never runs. Touches auth →
needs a security review per `CLAUDE.md`, and should be its own issue + branch. **Not fixed.**

## Frontend

`C:\Users\ACER\Desktop\aibless\feature_flag_ui` — Vite 8 + React 19. `npm install` was needed
(no `node_modules`). `npm run dev` → http://localhost:5173. Backend runs on **8081**
(`server.port=8081`), matching the FE's `.env`; CORS already allows `localhost:5173`. Port 8080 on
this machine is an unrelated BookAI app.

## Next steps

1. Get PR #65 reviewed/merged, then open the PR for #49 (base `develop` after the rebase, or the
   Tier-1 branch before it) and run `.claude/scripts/issue-board.sh ready 49`.
2. File the login-500 bug as its own issue.
3. Remaining follow-ups: unfreeze R7 by breaking the `config` ↔ `security` cycle (relocate
   `JwtProperties`); consider a meta-test that asserts each arch rule fires against a synthetic
   violating class, so the negative proofs stop living only in prose.
