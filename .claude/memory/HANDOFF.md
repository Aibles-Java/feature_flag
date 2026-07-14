# Handoff

*Ephemeral — overwritten by `/save-memory` at the end of each session. Read this first.*

## Current WIP

**Issue #23** (externalize secrets, fail fast on placeholder config) on branch
`feature/issue-23-externalize-secrets` (→ `develop`), PR **#39**. Addressing the
`/review-pr` findings and clearing the `CONFLICTING` state (2026-07-14):

- **Merged current `origin/develop` into the branch** (no rebase/force-push). Conflicts
  resolved: `Dockerfile` (kept develop's `EXPOSE 8081` + `/actuator/health/readiness`
  HEALTHCHECK from #25/PR #42, added only `ENV SPRING_PROFILES_ACTIVE=prod`; dropped the
  now-redundant `EXPOSE`), `.gitignore` (union), `JwtTokenProvider(.java|Test)` (develop's
  google-format + the `JwtProperties` constructor), `.claude/memory/*`.
- **Datasource fail-fast gap fixed:** new `config/RequiredDataSourceEnvPostProcessor`
  (`EnvironmentPostProcessor`, prod-only) aborts startup naming any missing/blank
  `SPRING_DATASOURCE_URL/USERNAME/PASSWORD`. Previously only `APP_JWT_SECRET` got a clear
  error; the datasource vars bound the literal `${VAR}` and failed later in Hikari.
- **Docs corrected:** `application-prod.properties` comment + `README.md` now describe the
  real behavior (datasource fail-fast is via the post-processor, not "Could not resolve
  placeholder").
- **Memory renumber:** on-branch `decisions/0008-secrets-externalization-fail-fast.md`
  → `0016` (develop took 0008–0015 meanwhile); MEMORY.md + `[[links]]` updated.

## Context to Load

- `decisions/0016-secrets-externalization-fail-fast.md` — the design + the two review fixes.
- `conventions/springboot-configprops-binding-gotchas.md` — the `${VAR}`-literal binder gotcha
  that motivates the datasource post-processor.

## Next steps

1. `./mvnw verify` green in the worktree (Spotless + tests + JaCoCo) — confirm before push.
2. Commit the merge + fixes; push `feature/issue-23-externalize-secrets` (normal push, no force).
   Memory gate satisfied (this file + MEMORY.md + decision are in the commit).
3. Confirm PR #39 flips to MERGEABLE; reply on the review thread that findings are addressed.

## Follow-ups (carried over)
- **Codegraph (#48/#49/#50)** on the board; #48 (Tier-1 ArchUnit gate) next to pick up.
- **`docs/` case collision:** `docs/ARCHITECTURE.md` vs `docs/architecture.md` — delete one on a
  case-sensitive box.
- Two `decisions/0012-*` files (micrometer + harness-guards) still collide — renumber one later.
- **#25:** Dockerfile HEALTHCHECK readiness→liveness? add DB-down readiness→503 test.
- **#26:** per-IP SDK limit for invalid keys; Redis backend for multi-instance.
- **#24:** make `feature_flags.key` H2-safe so SDK eval can be tested for a real 200.
- **Raise `jacoco.line.coverage`** as coverage climbs.
