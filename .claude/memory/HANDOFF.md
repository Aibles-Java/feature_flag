# Handoff

*Ephemeral — overwritten by `/save-memory` at the end of each session. Read this first.*

## Current WIP

**Issue #28** (structured JSON logging with request correlation) on branch
`feature/issue-28-structured-json-logging` (→ `develop`, branched fresh off updated
`origin/develop`). **Implementation complete, verified, code-reviewed (no findings).**
Not yet committed/pushed; no PR yet.

Staged set (exactly 11 files, `docs/architecture.md` case-collision artifact kept OUT):
- New: `logging/RequestCorrelationFilter.java`, `logging/MdcKeys.java`, `config/LoggingConfig.java`,
  `src/main/resources/logback-spring.xml`, + 3 tests
  (`RequestCorrelationFilterTest`, `GlobalExceptionHandlerTest`, `LogbackProdEncoderConfigTest`).
- Edited: `security/JwtAuthenticationFilter.java` (+userId MDC), `security/ApiKeyAuthenticationFilter.java`
  (+envId MDC), `exception/GlobalExceptionHandler.java` (+requestId in ProblemDetail), `pom.xml`
  (+`logstash-logback-encoder:8.0`).

`./mvnw verify` green: 195 tests, Spotless clean, JaCoCo 0.83 floor met. See
`decisions/0015-structured-json-logging-request-correlation.md`.

## Next steps
1. **Commit** the staged #28 change + this memory together (the memory gate blocks a code push
   with no `.claude/memory/` change). Conventional-commit `feat(ops):`.
2. **Push** the branch. NOTE: `gh` CLI is **not installed on this Windows machine** — `git push`
   works, but `issue-board.sh` (board start/ready) and `create-pr` (PR open) both need `gh`.
   Either install/auth `gh` (`gh auth refresh -s project` for board), or open the PR via the
   GitHub web compare URL and move the board card by hand. Board step 1 ("start" → In progress)
   was **skipped** for the same reason.
3. Open PR (base `develop`, `Closes #28`), then `issue-board.sh ready 28` once `gh` is available.

## Context to Load
- `decisions/0015-structured-json-logging-request-correlation.md` — the #28 design + ordering/MDC rationale.
- `conventions/spring-security-filter-order-anchor.md` — related: per-chain filter ordering
  (this change instead orders the whole servlet filter BEFORE `FilterChainProxy`).

## Known repo issue (pre-existing, not #28)
- **`docs/` case collision:** BOTH `docs/ARCHITECTURE.md` (long design doc) and
  `docs/architecture.md` (short generated overview) are git-tracked, differing only by case. On
  this case-insensitive Windows FS only one physical file exists, so git ALWAYS reports one as
  modified after checkout of the other — expect a persistent `M docs/ARCHITECTURE.md` in status.
  Fix on a case-sensitive box: delete one path. A separate uncommitted rewrite of this file
  (author oanhhkim) is stashed as `stash@{0}` on the #29 branch context — land/discard separately.

## Follow-ups (carried over)
- **#29** (Micrometer/Prometheus) PR #44 — was awaiting push/reply when this session started;
  branch untouched here. Cardinality documented (`6eb7d28`); revisit env tag only at thousands of tenants.
- **Numbering:** two `decisions/0012-*` files (micrometer + harness-guards) still collide — renumber one later.
- **Codegraph (#48/#49/#50)** on the board; #48 (Tier-1 ArchUnit gate) next to pick up.
- **#25:** Dockerfile HEALTHCHECK readiness→liveness? add DB-down readiness→503 test.
- **#26:** per-IP SDK limit for invalid keys; Redis backend for multi-instance.
- **#24:** make `feature_flags.key` H2-safe so SDK eval can be tested for a real 200.
- **Raise `jacoco.line.coverage`** above 0.83 as coverage climbs.
