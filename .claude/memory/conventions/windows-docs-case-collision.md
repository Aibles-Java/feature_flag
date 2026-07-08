---
name: windows-docs-case-collision
description: two git-tracked files differ only by case (docs/ARCHITECTURE.md vs docs/architecture.md) → permanently-dirty tree on Windows; only stage explicit paths, resolve by deleting one
metadata:
  type: convention
---

# `docs/ARCHITECTURE.md` vs `docs/architecture.md` — case collision on Windows

The repo tracks **two** files that differ only in case:

- `docs/ARCHITECTURE.md` (uppercase) — on `develop`, the big ~711-line "Architecture & Solution
  Design" rewrite.
- `docs/architecture.md` (lowercase) — the old ~86-line "Architecture Overview" stub.

On Windows (case-insensitive filesystem) only **one** physical file can exist for both index
entries, so whatever content is on disk makes the *other* path show as modified. `git restore`
one → the other flips to `M`. It's whack-a-mole; you **cannot** get a fully clean tree on Windows
while both entries exist.

**Consequences / how to work around it:**

- When committing unrelated work, **never `git add -A` / `git add .`** — stage explicit paths
  (`git add Dockerfile docker-compose.yml …`) so the phantom `M docs/architecture.md` doesn't
  ride along. (Issue #27 was committed this way.)
- The residual `M docs/architecture.md` in `git status` is expected noise, not your change.

**Real fix (follow-up, not done yet):** delete one of the two — almost certainly remove the
lowercase `docs/architecture.md` stub (the uppercase rewrite is the current doc), committed from
a Linux/macOS box or via `git rm --cached` so the case rename actually takes. Until then, treat
the dirty entry as background noise.
