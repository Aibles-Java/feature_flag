# Handoff

*Ephemeral — overwritten by `/save-memory` at the end of each session. Read this first.*

## Current WIP

**Code graph adoption** on branch `feature/codegraph-adoption`. Research + planning done;
**no implementation yet**. About to commit + push + open a PR into `develop` for the
spec/planning artifacts.

Files to commit this session (docs/planning only — NO source code):
- `docs/specs/codegraph-adoption.md` — the spec: Track A (ArchUnit governance) + Track B
  (CodeGraphContext MCP) + §9 Mermaid solution-design diagram (4-colour scheme; render
  verified via mermaid-cli → valid SVG).
- `.claude/skills/estimate-issue/calibration.md` — 3 estimate rows (#48/#49/#50).
- `.claude/memory/**` — this commit (decision 0014 + eventual-consistency convention + index).

**Deliberately NOT committed:** `docs/ARCHITECTURE.md` — a large uncommitted −688/+63 change
by another author (oanhhkim), unrelated to this work. Still parked (see below).

**GitHub issues filed** on Digital banking board (project #3), estimates written & verified:
- **#48** (M/5h) Track A Tier-1 ArchUnit gate + ADR-0003 + memory — core, touches CI. Blocks #49.
- **#49** (S/3h) Track A Tier-2 custom conditions. Depends on #48.
- **#50** (S/2h) Track B CodeGraphContext spike. Independent.

## Context to Load

- `decisions/0014-codegraph-adoption.md` — the decision, tool comparison, tier split, key gotchas.
- `docs/specs/codegraph-adoption.md` — full spec + solution-design diagram.
- `conventions/issue-board-estimate-eventual-consistency.md` — why `estimate` fails on run 1.

## Next steps
1. **Push** `feature/codegraph-adoption` (memory gate now satisfied by this commit) and open
   a PR into `develop` via the `create-pr` skill. PR is planning-only (spec + estimates + memory).
2. **Implement #48 first** (Track A Tier-1): `issue-board.sh start 48`, add `archunit-junit5:1.4.2`,
   write `src/test/java/org/aibles/feature_flag/architecture/ArchitectureTest.java` (R1–R7),
   prove the gate with a deliberate violation → `./mvnw verify` fails → revert, add ADR-0003.
   Remember: ArchUnit is static-only → Boot-4.1 test landmines do NOT apply; use `FreezingArchRule`
   if current code has pre-existing layering violations.
3. Then **#50** spike (~1 week later), then **#49** Tier-2. Re-evaluate jQAssistant only if
   Tier-3 governance or agent-query precision becomes a felt need.

**Parked / cross-branch (from prior sessions):**
- Unrelated `docs/ARCHITECTURE.md` change still uncommitted — land or discard separately.
- Issue #10 (`feature/issue-10-jwt-deleted-user-500`), #17 (`feature/issue-17-estimate-issue-skill`)
  — commit/push/PR/`ready` pending.
- Issue #14 (SonarQube) waiting on infra, holds `decisions/0006-*`.

**Follow-ups (from earlier work):**
- **#25:** reconsider Dockerfile HEALTHCHECK `readiness` → `liveness`; add DB-down readiness→503 test.
- **#26:** per-IP SDK limit for invalid keys; Redis backend for multi-instance.
- **#24:** make `feature_flags.key` H2-safe so SDK eval can be tested for a real 200.
- **Code graph:** jQAssistant+Neo4j upgrade (closes Tier-3); Joern for a future auth taint pass.
