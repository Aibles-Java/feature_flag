# 0007 — Estimation is a skill (`/estimate-issue`), hours-calibrated, human-confirmed

**Date:** 2026-07-02 · **Issue:** #17 · **Status:** accepted

## Decision

Task estimation for the Digital banking board is encoded as a skill
(`.claude/skills/estimate-issue/`) plus an `estimate <issue#> <SIZE> <hours>`
subcommand in `.claude/scripts/issue-board.sh`, not as a subagent:

- **Rubric in hours** (XS ≤1h, S 1–3h, M 3–8h, L 8–16h, XL >16h → split into
  sub-issues) because the board readme tracks team capacity in hours/day, not
  points.
- **Propose → human confirms → write.** The skill never sets board fields
  (`Size` single-select + `Estimate` number) before the human approves.
- **Calibration log** at `.claude/skills/estimate-issue/calibration.md`
  (append-only; `Actual`/`Δ` filled when the issue reaches Done) so the rubric
  gets feedback from every closed issue.
- `issue-workflow` gained an optional **step 0** pointing at this skill.

## Why not an agent

Estimation is a repeatable *procedure* that benefits from main-conversation
context (the issue is usually being discussed right before estimating); agents
buy context isolation we don't want here. Heavy codebase scoping can still be
delegated to a read-only Explore subagent from inside the skill.

## Constraints honored

- Estimates go in **board fields, not issue comments** — the auto-mode
  classifier blocks `gh issue comment` on non-current issues
  ([[decision-comments-cross-issue-blocked]]), and estimation happens before a
  branch exists.
- All card writes route through `issue-board.sh` for the repo+number filter
  ([[shared-board-repo-scoping]]).

## Numbering note

`decisions/0006-*` exists only on the parked `feature/issue-14` branch (SonarQube);
this file takes 0007 to avoid a collision when that branch lands.
