# Handoff

*Ephemeral — overwritten by `/save-memory` at the end of each session. Read this first.*

## Current WIP

**Issue #17** (`/estimate-issue` skill) on branch `feature/issue-17-estimate-issue-skill`
(→ `develop`). Implemented and verified:

- `.claude/scripts/issue-board.sh` — new `estimate <issue#> <SIZE> <hours>` subcommand
  (Size + Estimate board fields; allow-list + numeric validation; guarded two-step
  write with an explicit inconsistency message if the second write fails).
- `.claude/skills/estimate-issue/` — SKILL.md (hours rubric, evidence→propose→confirm→write
  procedure) + `calibration.md` seeded with #17's own row (M / 4h).
- `issue-workflow/SKILL.md` — optional step 0 pointing at the new skill.

code-reviewer pass done: HIGH (unguarded partial write) + MEDIUM (SIZE reaching jq
unvalidated) fixed; error paths + happy path re-tested live against card #17.
Remaining this session: commit, push, PR (`Closes #17`), `issue-board.sh ready 17`.

## Context to Load

- `decisions/0007-estimate-issue-skill.md` — why skill-not-agent, rubric, constraints.
- `conventions/issue-board-args-need-allowlist.md` — jq-interpolation gotcha when
  extending `issue-board.sh`.

## Next steps

- If not already done: commit + push this branch, open PR, move card to Ready For Testing.
- First real use of `/estimate-issue` on a fresh issue → fill #17's `Actual (h)` in
  `calibration.md` when it reaches Done (first calibration data point).
- Deferred LOW from review: `set_estimate()` makes ~5 `gh` round-trips (field-list
  re-fetches) — collapse if it ever feels slow.
- Still parked: uncommitted `.gitignore` (adds `.omc/`) + regenerated
  `docs/ARCHITECTURE.md` — land or discard separately; issue #14 branch (SonarQube)
  waits on self-hosted infra and holds `decisions/0006-*`.
- Still open from #15: verify a live decision comment, then tick its last acceptance box.
- Follow-up from #3/#4: raise `jacoco.line.coverage` above 0.00.
