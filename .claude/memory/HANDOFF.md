# Handoff

*Ephemeral — overwritten by `/save-memory` at the end of each session. Read this first.*

## Current WIP

**Issue #8** (workflow automation) is being wrapped up on branch
`feature/issue-8-workflow-automation` (→ `develop`). Work is committed; PR is being
opened this session. Board card for #8 is assigned to @trinhvandat and at **In progress**
(will move to **Ready For Testing** once the PR opens).

Files added/changed for #8:
- `.claude/scripts/issue-board.sh` — `start|ready|done|status <issue#>` on the Digital
  banking board (project #3); assign + status, IDs resolved at runtime.
- `.claude/hooks/pre-push-memory-gate.sh` — blocks code pushes lacking `.claude/memory/`
  changes; wired as `PreToolUse` on Bash `git push` + `.githooks/pre-push` backstop.
- `.claude/skills/issue-workflow/SKILL.md`, `CLAUDE.md` (dev-workflow pointer),
  `.claude/settings.json` (PreToolUse gate).

Still open from prior sessions: **PR #7** (JaCoCo + CI), plus PR #1 and PR #2 — all →
`develop`, unmerged.

## Context to Load

- `decisions/0005-issue-workflow-board-and-memory-gate.md` — before touching the board
  script, the memory gate, or the issue-workflow skill (incl. the `gh` `project` scope
  requirement and exact board status names).
- `conventions/hook-changes-require-explicit-confirmation.md` — before editing hooks in
  `settings.json`.
- `decisions/0004-jacoco-coverage-ratchet-and-ci.md` — before touching JaCoCo / `ci.yml`.

## Next steps

- Open PR for #8 → `develop`, then `issue-board.sh ready 8`.
- Review/merge PR #8, then PR #7, PR #1, PR #2.
- Note: `docs/ARCHITECTURE.md` and `.gitignore` have unrelated uncommitted WIP in the
  working tree — left out of the #8 commit deliberately; someone should land or discard them.
