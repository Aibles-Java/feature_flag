# 0010 — Project-aware review-pr skill (fork of official /code-review)

**Date:** 2026-07-09

## Decision

Added `.claude/skills/review-pr/SKILL.md` (`/review-pr`) — a project-local **fork** of
Claude Code's official `/code-review` command
(`plugins/claude-plugins-official/code-review/commands/code-review.md`). Both official
review commands are plain Markdown prompt files, so wrapping/forking them is just authoring
another prompt file — confirmed before building.

Kept the upstream pipeline that makes `/code-review` good (5 parallel reviewers → Haiku
0–100 confidence scoring → drop <80, which suppresses false positives) and **added a 6th
reviewer** seeded with this repo's sensitive areas from `CLAUDE.md`:

- two security chains + filter order (SDK order=1 / Admin order=2), anchored on a standard
  filter — see [[spring-security-filter-order-anchor]]
- immutable `FeatureFlag.key`
- Liquibase: new changeset only, Postgres-only SQL guarded on H2 — see
  [[liquibase-postgres-only-migrations-on-h2]]
- `PermissionService` role checks on every mutation
- `ApiKeyGenerator` / hashed SDK keys — see [[0008-hash-sdk-api-keys-at-rest]]
- JWT filter catch scope — see [[jwt-filter-catch-scope]]
- `FlagEnvironmentState` one-row-per-env invariant

**Two modes**, chosen by argument: no arg → LOCAL (diff vs base branch, printed report,
pre-push self-review); PR number → GITHUB (review + `gh pr comment`, matching upstream).
Base-branch rule reused from [[0002-pr-template-and-create-pr-skill]] (`feature/*` →
`develop`).

## Why

The user wanted the official reviewer made "more detailed for our project." The official
`/code-review` already loads root + per-directory `CLAUDE.md`, but a generic agent only
*infers* the subtle conventions. Forking lets us make the sensitive-area checks explicit and
non-optional while keeping the proven false-positive suppression. Chose a **skill** (over a
command/agent) for consistency with the repo's existing `.claude/skills/` pattern and
keyword auto-trigger; a new agent would duplicate the OMC `code-reviewer`/`security-reviewer`
personas without encoding the workflow.

## Gotcha

Fork ≠ inherit: there is no include mechanism, so if Anthropic updates the upstream
`code-review.md`, Steps 3–6 of our skill must be re-synced by hand. Noted in the skill's
Notes section.

## Outcome

Skill authored and frontmatter-validated. Not yet exercised on a real PR — first run
(`/review-pr` local, or `/review-pr <PR#>`) will calibrate the sensitive-area list. Shipped
on `feature/review-pr-skill` → `develop`. `gh` is not installed in this environment, so the
PR itself is opened via the GitHub compare URL rather than `gh pr create`.
