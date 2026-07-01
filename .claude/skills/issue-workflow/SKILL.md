---
name: issue-workflow
description: >
  End-to-end loop for working a GitHub issue in feature_flag: assign it, move the
  project board card through In progress → Ready For Testing, and guarantee memory
  is saved before the work is pushed. Apply whenever you are handed a GitHub issue
  link/number to implement. Trigger phrases: "work on this issue", "do this issue",
  "implement issue", "start on <issue-link>", "pick up issue".
metadata:
  template_version: "1.0.0"
argument-hint: "[issue-number-or-url]"
---

# Issue Workflow — feature_flag

The full lifecycle for turning a GitHub issue into a merged PR while keeping the
**Digital banking** project board (project #3) and repo memory in sync. Builds on
`git-workflow` (branching/commits) and `create-pr` (PR format); this skill adds the
board + assignee + memory-gate steps around them.

Helper script: `.claude/scripts/issue-board.sh` (needs `gh` with the `project`
scope — `gh auth refresh -s project` if a board step errors on scope).

## The loop

### 1 — Start work (do this the moment you pick up an issue)

```bash
.claude/scripts/issue-board.sh start <issue#>
```

This assigns the issue to the authenticated `gh` user and moves its board card to
**In progress** (adding it to the board first if it isn't there). Then branch:

- `git checkout -b feature/<slug> origin/develop` (see `git-workflow` for naming).

### 2 — Implement

Do the work. Follow the repo's plan-first / code-review / security-review gates in
`CLAUDE.md` for anything non-trivial or touching sensitive areas.

### 3 — Save memory BEFORE pushing (enforced)

Run `/save-memory` so decisions + `HANDOFF.md` are recorded, and commit it. The
**memory gate** (`.claude/hooks/pre-push-memory-gate.sh`, wired as a `PreToolUse`
hook on `git push` and as a `.githooks/pre-push` backstop) will **block any push
whose commits touch code but not `.claude/memory/`**. So memory always ships with
the work rather than as a disconnected afterthought.

If a push is legitimately memory-less (e.g. a pure typo fix), override explicitly:
`SKIP_MEMORY_CHECK=1 git push …`.

### 4 — Open the PR

Use the `create-pr` skill. Base is `develop` for `feature/*`. Reference the issue
(e.g. `Closes #<issue#>`) in the Related issue section.

### 5 — Move to Ready For Testing (right after the PR opens)

```bash
.claude/scripts/issue-board.sh ready <issue#>
```

The card moves to **Ready For Testing**, signalling the work is up for review/QA.

### (Optional) When merged / verified

```bash
.claude/scripts/issue-board.sh done <issue#>
```

## Board status vocabulary (exact, case-sensitive)

`Todo` · `In progress` · `Ready For Testing` · `Done` — the script resolves the
field/option IDs at runtime, so refer to statuses by these names.

## Shared, multi-repo board (important)

The "Digital banking" board (project #3) is **org-wide** and holds cards from several
repos, so an issue *number* is not unique across the board — e.g. `feature_flag#4` and
`banking-knowledge-base#4` coexist. `issue-board.sh` disambiguates by filtering every
card lookup on `.content.repository == "Aibles-Java/feature_flag"`; it only ever touches
this repo's cards. If you mutate the board by hand (raw `gh project ...`), you **must**
apply the same repo filter — a bare `.content.number==N` match can move another team's
card. (Regression once did exactly this; see issue #12.)

## Boundaries

- Don't move a card to **Ready For Testing** before the PR actually opens.
- Don't bypass the memory gate to "save time" — use `/save-memory`, not
  `SKIP_MEMORY_CHECK`, unless the push genuinely has no session context worth keeping.
- The board lives under the `Aibles-Java` org; the `gh` token must carry the
  `project` scope for any board mutation (read-only `read:project` is not enough).
