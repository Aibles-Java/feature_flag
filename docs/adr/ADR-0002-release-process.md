# ADR-0002: Release Process (develop → release → main)

**Status:** Accepted
**Date:** 2026-07-01

## Context

`CLAUDE.md` and the `git-workflow` skill already declare Gitflow branch roles
(`feature/*` → `develop`, `release/*` → `main` + back to `develop`,
`hotfix/*` → `main` + `develop`), but only the branch topology — not the
sequence of steps, versioning rules, or where a human must approve before
the agent proceeds. As Claude is increasingly used to drive git operations
directly, and `Bash(git:*)` is blanket-allowed in `.claude/settings.json`
(no per-command permission prompt), the process itself has to be the thing
that enforces pauses — the permission system won't.

## Decision

The release/hotfix process is documented as a "Release flow" section inside
the existing `git-workflow` skill, not a separate skill. Branch naming,
commit format, and release process are one continuous domain — splitting
them risks the two files drifting on branch names over time.

The process is trigger-gated and step-gated:

- **Never proactive.** The agent only starts a release or hotfix on
  explicit request. `develop` looking shippable is not a trigger.
- **Confirm at the risky steps only.** Branch creation, version bump,
  running tests, and opening a PR into `main` happen without asking
  (all reversible / non-shared-state). Merging into `main`, tagging,
  pushing tags, and merging back into `develop` each require the agent
  to stop and get explicit go-ahead first.
- **Merge-back to `develop` is mandatory**, never skipped, because a fix
  made only on `release/*` or `hotfix/*` and not carried back into
  `develop` is the most common cause of a regression reappearing in the
  next release.
- **Security review gate carries over.** Any release/hotfix touching
  `security/`, JWT config, `db/changelog/migrations/`, or
  `ApiKeyGenerator` runs the security-review skill before the PR to
  `main`, consistent with the existing sensitive-areas gate in
  `CLAUDE.md`.

## Consequences

- Cutting a release now always produces a version-bump commit and a PR
  into `main` (never a direct push to `main`), giving a reviewable
  artifact even for a 2-person team.
- The agent will stop mid-flow at each STOP point and wait — anyone
  invoking the release flow should expect multiple checkpoints rather
  than one end-to-end run.
- If the process needs to diverge from plain branch-naming conventions
  in the future (e.g. release trains, automated changelog generation),
  it's a `git-workflow` skill update, not a new file to discover.

## Alternatives considered

- **Separate `release-flow` skill** — considered because the release
  process is stateful/multi-step versus the mostly-stateless reference
  content already in `git-workflow`, and a distinct skill would allow
  narrower trigger phrases. Rejected in favor of a single source of
  truth for branch topology; revisit if `git-workflow` becomes
  unwieldy.
- **Full end-to-end automation on a single go-ahead** — rejected because
  merging to `main`, tagging, and merge-back are shared/hard-to-reverse
  actions per `CLAUDE.md`'s risk-of-action guidance; a single upfront
  approval doesn't give the user visibility into what actually happened
  at each step before the next one runs.
