# Handoff

*Ephemeral — overwritten by `/save-memory` at the end of each session. Read this first.*

## Current WIP

**Issue #14** (viewable code-coverage dashboard) — researched and **parked** on branch
`feature/issue-14-sonarqube-coverage-board` (off up-to-date `develop`). Human decided:
**self-hosted SonarQube**, infra in a **separate repository** (not yet created); this
repo only wires CI analysis + quality gate. Decision comment posted on the card
(first real use of the #15 convention):
<https://github.com/Aibles-Java/feature_flag/issues/14#issuecomment-4865687139>
No implementation yet — this branch carries only memory. Full plan (server-independent
slice vs. blocked-on-infra remainder) is in `decisions/0006`.

**#15 verification:** the convention IS now verified on a real decision, but ticking
the last acceptance checkbox in #15's body was **classifier-blocked** (cross-issue
`gh issue edit`). → Human ticks it, or agent does it in a session working #15.

## Context to Load

- `decisions/0006-selfhosted-sonarqube-coverage-board.md` — the #14 decision + agreed
  implementation plan (read before resuming #14).
- `conventions/decision-comments-cross-issue-blocked.md` — classifier scope gotcha
  (now includes `gh issue edit`).

## Next steps

- **When SonarQube infra exists** (user will say so): resume #14 on this branch —
  `issue-board.sh start 14`, then the plan in decisions/0006 (guarded `sonar:sonar`
  step, pom sonar props, ratchet 0.00→~0.25, jacoco.csv job summary, create README).
  Update #14's body from the stale Codecov plan first. Infra constraint to relay:
  server reachable from GitHub Actions runners + GitHub ALM configured.
- The server-independent slice of #14 was assessed as doable *now* if the user wants
  it before infra lands — they chose to wait.
- Tick the last acceptance box on #15 (see above).
- Still parked: uncommitted `.gitignore` (adds `.omc/`) + regenerated
  `docs/ARCHITECTURE.md` — land or discard separately.
- Open PRs from prior sessions: #8, #7, #2, #1 (all → `develop`, unmerged).
