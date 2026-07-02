# Handoff

*Ephemeral — overwritten by `/save-memory` at the end of each session. Read this first.*

## Current WIP

**Issue #15** (Decision comments for human-in-the-loop calls) on branch
`feature/issue-15-decision-comments` (→ `develop`). The `issue-workflow` skill
(`.claude/skills/issue-workflow/SKILL.md`) now documents the **Decision comments**
convention: after a substantive in-terminal decision (scope/plan change or spawned
follow-up) resolves, post a templated 🧑‍⚖️ comment to the linked issue via
`gh issue comment` with a quoted-heredoc body. Reviewed by code-reviewer (1 fix
applied: heredoc instead of single-quoted `--body`). Commit / push / PR happening
this session.

**Acceptance checkbox still open on #15:** “Verified on a real decision.” No live
human decision occurred this session; the retroactive post of the real #10-spawn
decision to issue #4 was **blocked by the auto-mode classifier** (cross-issue write),
and the user was AFK when asked how to proceed. → Verify on the next naturally
occurring live decision: post the comment to that session’s linked issue, then tick
the box on #15.

## Context to Load

- `conventions/decision-comments-cross-issue-blocked.md` — classifier scope gotcha +
  heredoc pattern for decision comments (new this session).
- `decisions/0005-issue-workflow-board-and-memory-gate.md` — board script + memory gate.

## Next steps

- This session (if not already done): commit skill + memory, push, open PR
  (`Closes #15`, note the unchecked verification criterion in Test plan), then
  `.claude/scripts/issue-board.sh ready 15`.
- Next live human-in-the-loop decision in any session: exercise the new convention
  for real, then check the last acceptance box on #15.
- Still parked: uncommitted `.gitignore` (adds `.omc/`) + regenerated
  `docs/ARCHITECTURE.md` — land or discard separately.
- Follow-up from #3/#4: raise `jacoco.line.coverage` above 0.00 now that the security
  package has real coverage.
- Open PRs from prior sessions: #8, #7, #2, #1 (all → `develop`, unmerged).
