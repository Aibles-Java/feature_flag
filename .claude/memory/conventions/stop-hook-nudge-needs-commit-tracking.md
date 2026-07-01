---
name: stop-hook-nudge-needs-commit-tracking
description: >
  The save-memory Stop-hook reminder must check for new commits, not just a
  dirty working tree, or it goes permanently silent for sessions that
  commit+push before stopping.
metadata:
  type: convention
---

`.claude/hooks/remind-save.sh` originally nudged `/save-memory` only when
`git status --porcelain` was non-empty. That's the wrong proxy for "real
work happened" in this repo: the normal flow (see [[0003-release-flow-in-git-workflow-skill]]
and the `git-workflow` skill) is to commit and push before stopping, so the
tree is clean at Stop time on every normal session — the reminder never
fired, and the user reported it as "not working."

**Fix applied:** the hook now also tracks the last-seen `HEAD` sha in
`.claude/memory/.last-seen-commit` and fires if `HEAD` moved since the last
check, in addition to the dirty-tree check. Still capped at once/day via
`.claude/memory/.nudge-guard`.

**Related gotcha:** both state files (`.nudge-guard`, `.last-seen-commit`)
must stay in `.gitignore` — they were previously untracked *and*
ungitignored, which meant that once `.nudge-guard` was created by a first
nudge, the working tree looked permanently "dirty" from then on regardless
of actual changes. Both are now listed in `.gitignore` under `### Claude Code ###`.

**How to apply:** any future change to `remind-save.sh`'s trigger condition
should account for *both* uncommitted changes and new commits since last
check — not dirty-tree alone. And any new hook state file written under
`.claude/memory/` needs a matching `.gitignore` entry, or it will pollute
`git status` for the life of the repo.
