# Handoff

*Ephemeral — overwritten by `/save-memory` at the end of each session. Read this first.*

## Current WIP

**Landing parked changes** on branch `feature/architecture-doc-and-gitignore`
(→ `develop`): two commits — `docs(architecture)` (full rewrite of
`docs/architecture.md` into a versioned solution-design document, +688/-63) and
`chore(gitignore)` (ignore `.omc` state directory). Push + PR happening this
session; nothing else touched.

**Issue #15 verification still open:** acceptance checkbox “Verified on a real
decision” remains unchecked. On the next naturally occurring live human-in-the-loop
decision (any session), post the templated 🧑‍⚖️ decision comment to that session's
linked issue, then tick the box on #15.

## Context to Load

- `conventions/decision-comments-cross-issue-blocked.md` — classifier scope gotcha +
  heredoc pattern for decision comments.
- `decisions/0005-issue-workflow-board-and-memory-gate.md` — board script + memory gate.

## Next steps

- This session (if not already done): push `feature/architecture-doc-and-gitignore`
  and open PR → `develop`.
- Next live human-in-the-loop decision in any session: exercise the decision-comment
  convention for real, then check the last acceptance box on #15.
- Follow-up from #3/#4: raise `jacoco.line.coverage` above 0.00 now that the security
  package has real coverage.
- Open PRs from prior sessions: #8, #7, #2, #1 (all → `develop`, unmerged) — plus the
  #15 PR if merged/unmerged status has changed.
