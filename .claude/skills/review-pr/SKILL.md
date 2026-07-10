---
name: review-pr
description: >
  Review a pull request (or the current local diff) for feature_flag with a
  project-aware, multi-agent pipeline. Forks Claude Code's official /code-review
  (5 parallel reviewers + 0-100 confidence scoring) and adds a dedicated
  reviewer for this repo's sensitive areas: the two security chains, immutable
  flag key, Liquibase migration rules, PermissionService role checks, and
  ApiKeyGenerator. Trigger phrases: "review pr", "review this pr", "review the
  diff", "review my changes", "/review-pr".
metadata:
  template_version: "1.0.0"
argument-hint: "[pr-number]   # omit to review the current branch's local diff"
---

# /review-pr

A project-specific fork of the official `/code-review` command. It keeps the
proven upstream pipeline (parallel reviewers + confidence scoring to suppress
false positives) and adds a review pass seeded with **this repo's landmines**,
so the subtle conventions in `CLAUDE.md` are checked explicitly rather than
inferred.

Builds on the same context as `create-pr` / `issue-workflow`. Use it before
opening a PR (local mode) or when reviewing an existing PR (GitHub mode).

## Modes

The mode is decided by whether a PR number argument (`$1`) is passed:

- **No argument → LOCAL mode.** Review the current branch's diff against its base
  branch. Findings are printed as a terminal report. Nothing is posted anywhere.
  Best for a pre-push self-review.
- **PR number → GITHUB mode.** Review the given PR and post the result as a `gh`
  PR comment (matching the official command's behavior). Best for reviewing a
  teammate's PR.

Determine the base branch from the branch name (same rule as `create-pr`):
`feature/*` → `develop`; `release/*` / `hotfix/*` → `main`; otherwise ask.

Requires the `gh` CLI authenticated for GITHUB mode. In LOCAL mode `gh` is not
needed.

---

## Step 0 — Resolve scope

**LOCAL mode:**
- `git branch --show-current` → derive base branch (rule above).
- `git diff <base>...HEAD --name-only` → changed files. If empty, stop and report
  "no changes vs `<base>`".
- Use `git diff <base>...HEAD` as the change set for all reviewers below.

**GITHUB mode:**
1. Use a **Haiku** agent to check whether the PR (a) is closed, (b) is a draft,
   (c) obviously needs no review (trivial/automated), or (d) already has a review
   comment from you. If any apply, stop and say why.
2. `gh pr diff <n>` and `gh pr view <n>` are the change set.

## Step 1 — Load project context

Use a **Haiku** agent to return the *paths* (not contents) of relevant
`CLAUDE.md` files: the root `CLAUDE.md`, plus any `CLAUDE.md` in directories the
diff touches. Also note that `.claude/memory/` holds durable conventions — the
reviewers may consult it for prior decisions relevant to the changed files.

## Step 2 — Summarize the change

Use a **Haiku** agent to produce a short summary of what the diff does. This
frames the parallel reviews.

## Step 3 — Parallel review (fork of the official 5 + 1 project agent)

Launch these agents **in parallel** (Sonnet unless noted). Each returns a list of
issues; for each issue it states the reason it was flagged (CLAUDE.md adherence,
bug, historical context, comment guidance, or **project sensitive area**).

- **Agent 1 — CLAUDE.md compliance.** Audit the diff against the root and
  directory `CLAUDE.md` files. (CLAUDE.md is guidance-for-writing, so not every
  line applies to review — use judgment.)
- **Agent 2 — Shallow bug scan.** Read only the diff; flag large/obvious bugs.
  Skip nitpicks and likely false positives.
- **Agent 3 — Historical context.** Read `git blame`/history of the modified
  code; flag bugs that only surface in light of that history.
- **Agent 4 — Prior-PR context.** Read previous PRs touching these files; surface
  earlier review comments that still apply.
- **Agent 5 — Comment guidance.** Read code comments in the modified files; verify
  the changes honor any guidance written there.
- **Agent 6 — feature_flag sensitive areas (PROJECT).** Review the diff
  specifically against this repo's landmines. Flag a finding only when the diff
  actually violates one:
  - **Two security chains / filter order.** `security/` changes: SDK chain
    (`/api/v1/sdk/**`, order=1, `ApiKeyAuthenticationFilter`) vs Admin chain
    (order=2, `JwtAuthenticationFilter`) must stay separate and correctly ordered.
    Custom filters must be anchored on a standard filter (e.g.
    `UsernamePasswordAuthenticationFilter`), never on another custom filter
    (see memory: `spring-security-filter-order-anchor`).
  - **Immutable flag key.** `FeatureFlag.key` must never be updated after
    creation; `update()` must keep ignoring it.
  - **Liquibase.** Never modify an already-run changeset in
    `db/changelog/migrations/`; changes must be a NEW changeset. Postgres-only
    SQL (pgcrypto/extensions) must be guarded `dbms="postgresql"` so the H2 test
    run doesn't break (memory: `liquibase-postgres-only-migrations-on-h2`).
  - **Permission checks.** Every mutating service path must go through
    `PermissionService` (OWNER/ADMIN/VIEWER) — authorization stays in services,
    not controllers.
  - **Secrets / API keys.** `ApiKeyGenerator` must stay `SecureRandom`-based; SDK
    keys are stored hashed (SHA-256) with one-time plaintext reveal — no plaintext
    key persisted or logged (memory: `0008-hash-sdk-api-keys-at-rest`).
  - **JWT filter scope.** Catch both `UsernameNotFoundException` and
    `JwtException`; valid-token/missing-subject is `log.warn`
    (memory: `jwt-filter-catch-scope`).
  - **FlagEnvironmentState invariant.** A flag has exactly one state row per
    environment — new-flag creation must fan out state to every existing env;
    never query flags without that join.

## Step 4 — Confidence scoring (suppress false positives)

For **each** issue from Step 3, launch a parallel **Haiku** agent that scores it
0–100 for confidence it is real (give this rubric verbatim):

- **0** — Not confident; false positive under light scrutiny, or a pre-existing
  issue.
- **25** — Somewhat; might be real, couldn't verify. Stylistic issues not
  explicitly called out in the relevant CLAUDE.md land here.
- **50** — Moderately; verified real but a nitpick / rare / low importance.
- **75** — Highly; double-checked, likely hit in practice, current approach
  insufficient, or directly named in the relevant CLAUDE.md.
- **100** — Certain; confirmed, frequent, evidence directly proves it.

For CLAUDE.md-flagged issues, the scorer must confirm the CLAUDE.md actually
calls out that specific issue. **Project sensitive-area findings (Agent 6)** are
held to the same bar — confirm the diff genuinely violates the named convention.

## Step 5 — Filter

Drop every issue scoring **< 80**. If none remain, report a clean review.

## Step 6 — Output

**Ignore false positives** throughout (pre-existing issues, linter/compiler-caught
problems, pedantic nitpicks, general quality gaps not required by CLAUDE.md,
issues on lines the PR didn't modify, intentional related changes). Do **not**
build or typecheck — CI handles that.

**LOCAL mode** — print to the terminal:

```markdown
### Code review — <branch> vs <base>

Found N issue(s):

1. <brief description> (<reason, e.g. CLAUDE.md says "…" / sensitive area: Liquibase>)
   `path/to/File.java:LINE`

2. …

<or, if clean:>
No issues found. Checked for bugs, CLAUDE.md compliance, and feature_flag sensitive areas.
```

**GITHUB mode** — before posting, re-run the Step 0.1 eligibility check with a
Haiku agent (the PR may have changed). Then `gh pr comment <n>` with the same
content, following the official formatting rules:
- Brief, no emojis (except the trailer), cite/link each finding.
- Link code with a **full commit SHA**:
  `https://github.com/<owner>/<repo>/blob/<full-sha>/<path>#L<start>-L<end>`
  (at least one line of context each side; do not use `$(...)` in the link).
- End with:

```markdown
🤖 Generated with [Claude Code](https://claude.ai/code)

<sub>- If this review was useful, react 👍, otherwise 👎.</sub>
```

## Notes

- Make a todo list first.
- This is a fork of the official `/code-review`. If Anthropic updates
  `plugins/code-review/commands/code-review.md`, re-sync Steps 3–6 here.
- For a heavier cloud multi-agent pass, the built-in `/code-review ultra <PR#>`
  is still available and complementary.
