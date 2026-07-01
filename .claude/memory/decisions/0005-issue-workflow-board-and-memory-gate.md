# 0005 — Issue-workflow automation: board status, assignee, and a pre-push memory gate

**Date:** 2026-07-01 · **Issue:** #8 · **Branch:** `feature/issue-8-workflow-automation`

## What was decided

Wire three workflow improvements into the harness so working a GitHub issue keeps
the **Digital banking** GitHub Project (project #3) and repo memory in sync:

1. **Assignee + board status** via `.claude/scripts/issue-board.sh`:
   - `start <issue#>` → assign to the authenticated `gh` user + move card to **In progress**
   - `ready <issue#>` → **Ready For Testing**; `done <issue#>` → **Done**; `status <issue#>` prints it
   - Project/field/option IDs are resolved **at runtime** (not hardcoded) so board edits don't break it.
2. **Pre-push memory gate** (`.claude/hooks/pre-push-memory-gate.sh`): blocks a push whose
   commits (`@{upstream}..HEAD`, falling back to `origin/develop..HEAD` for new branches)
   touch code but **not** `.claude/memory/`. Wired as a `PreToolUse` hook on Bash `git push`
   (exit 2 → surfaces the reason to Claude) and as a `.githooks/pre-push` backstop (exit 1).
   Escape hatch: `SKIP_MEMORY_CHECK=1`.
3. **`issue-workflow` skill** documenting the end-to-end loop (start → branch → implement →
   /save-memory → PR → ready). `CLAUDE.md` dev-workflow section points at it.

## Why

The code side of the issue loop was already automated (branch + PR); the PM side was
manual and there was no guard against pushing work without saving session memory. The
gate makes "memory ships with the work" enforceable rather than a habit.

## Key facts / gotchas

- **`gh` needs the `project` scope** (not just `read:project`) for any board mutation.
  `read:project` only lists/reads. Refresh with `gh auth refresh -s project`.
- Board status names are **case-sensitive and exact**: `Todo` · `In progress` ·
  `Ready For Testing` · `Done` (note lowercase "progress", capital "For"/"Testing").
- The git backstop needs `git config core.hooksPath .githooks` once per clone (set in
  this repo during the session).
- #1 and #3 could NOT be pure hooks — Claude Code has no "started work" / "PR opened"
  event — so they're a helper script the `issue-workflow` skill invokes, not automation
  that fires by itself. Only #2 (git push) maps to a real interceptable event.

## Alternatives considered

- **Warn-only gate** — rejected; user chose block-until-saved for a hard guarantee.
- **Claude-only gate (no git hook)** — rejected; added the `.githooks/pre-push` backstop
  (with `SKIP_MEMORY_CHECK` escape) so manual/human pushes are covered too.
- **Hardcoding board IDs** — rejected in favour of runtime resolution.

See [[hook-changes-require-explicit-confirmation]] — the `settings.json` PreToolUse
wiring was explicitly confirmed by the user before editing.
