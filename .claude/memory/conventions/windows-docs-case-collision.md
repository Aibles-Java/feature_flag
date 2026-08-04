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

**Resolved.** `develop` renamed the uppercase file to `docs/architecture-design-v1.md` (a
case-distinct name), and issue #27's branch dropped its own `docs/ARCHITECTURE.md` index entry via
`git rm --cached` at the merge. Once both are in, the tree is genuinely clean on Windows.

**Why the `git rm --cached` was mandatory, not cosmetic:** while both entries exist the phantom path
is *always* dirty, so `git merge` refuses to start — "Your local changes to the following files
would be overwritten by merge: docs/ARCHITECTURE.md" — and `git restore` / `git stash push` just
flips which of the two names is dirty. Dropping the index entry is the only way out; stashing is
not.
