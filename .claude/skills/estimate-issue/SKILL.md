---
name: estimate-issue
description: >
  Produce a consistent, evidence-based estimate for a feature_flag GitHub issue
  and record it on the Digital banking board (Size + Estimate fields) using an
  hours-calibrated rubric. Trigger phrases: "estimate issue", "estimate this
  task", "size this issue", "how big is issue", "/estimate-issue".
metadata:
  template_version: "1.0.0"
argument-hint: "<issue#>"
---

# /estimate-issue

Turns a GitHub issue into a Size + Estimate on the **Digital banking** board
(project #3) via a fixed procedure, so estimates are comparable across issues and
across whoever produced them. The board readme tracks team capacity in **hours per
day**, so estimates are in hours — not story points.

Helper: `.claude/scripts/issue-board.sh estimate <issue#> <SIZE> <hours>` (handles
the repo+number card matching and adds the card to the board if missing — never
mutate Size/Estimate with raw `gh project` commands; see the multi-repo warning in
`issue-workflow`).

## Rubric (hours → Size)

Size values are exact and case-sensitive: `XS` · `S` · `M` · `L` · `XL` (same
convention as `issue-workflow`'s status vocabulary — the script matches them
verbatim against the board's options).

| Size | Hours | Guidance |
|------|-------|----------|
| XS | ≤ 1h | Trivial: one file, no unknowns |
| S | 1–3h | Small, well-understood change |
| M | 3–8h | Multi-file change or one meaningful unknown |
| L | 8–16h | Multiple workstreams; consider splitting |
| XL | > 16h | **Do not estimate as one card** — recommend splitting into sub-issues sized ≤ L |

When a task straddles a boundary, round **up** — historical bias is underestimation
(check `calibration.md` before deciding).

## Steps

### 1 — Read the issue

`gh issue view <issue#> --repo Aibles-Java/feature_flag` — body, checklists,
comments. If the body is too vague to scope (no concrete deliverable), stop and say
what's missing instead of guessing a number.

### 2 — Gather evidence (never estimate from the title alone)

- **Code scoping:** list the concrete files/classes/layers the work touches. Search
  the codebase; count real files, don't hand-wave "the service layer".
- **Landmines:** scan `.claude/memory/MEMORY.md` for entries touching the same area
  (e.g. Spring Boot 4.1 MockMvc gotchas for anything test-related). Each relevant
  landmine adds time or an unknown.
- **Calibration history:** read `calibration.md` (this skill's directory) for
  similar past issues and their estimated-vs-actual deltas.

### 3 — Draft the breakdown

Present to the human:

- Subtasks with hours each (evidence-linked: "MockMvc tests for 3 controllers ~4h,
  reusing the #4 pattern"), the total, and the mapped Size.
- Risks/unknowns that could blow the estimate.
- If total lands in XL: a proposed split into sub-issues instead of one number.

### 4 — Confirm, then write (never write unconfirmed)

Only after the human approves (they may adjust the number — theirs wins):

```bash
.claude/scripts/issue-board.sh estimate <issue#> <SIZE> <hours>
```

### 5 — Log the estimate

Append a row to `calibration.md` with the issue, date, Size, estimated hours, and
a one-line basis. Leave `Actual` and `Δ` empty for now.

## Calibration (closing the loop)

When an estimated issue reaches **Done** (typically alongside
`issue-board.sh done <issue#>`), fill in its `Actual (h)` and `Δ` columns in
`calibration.md` — ask the human for actual hours if they aren't evident from the
session. Persistent skew (e.g. M-sized issues routinely running 2×) is a signal to
adjust the rubric; propose the change rather than silently applying it.

## Boundaries

- **Propose → confirm → write.** Never set board fields before the human approves
  the number.
- **Estimates live in board fields, not issue comments.** The auto-mode classifier
  blocks `gh issue comment` on issues other than the current branch's (see
  `issue-workflow` / issue #15), and estimation usually happens *before* work
  starts — field edits via the script are the safe channel.
- Hours, not story points — capacity on this board is tracked in hours/day.
- Don't relax the repo filter: always go through `issue-board.sh`, never raw
  `gh project item-edit` against a card matched by number alone.
- An estimate is not a commitment to start the work — do not assign the issue or
  move its Status; that's `issue-workflow` step 1.
