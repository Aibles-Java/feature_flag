# 0003 — Release/hotfix flow added to git-workflow skill, not a new skill

**Date:** 2026-07-01

## Decision

Brainstormed with the user whether the develop → release → main release/hotfix process
should be a new standalone skill or an extension of the existing `git-workflow` skill.
Presented both options plus an autonomy-level question via `AskUserQuestion`. User chose:

- **Extend `git-workflow`** (not a separate `release-flow` skill) — keeps branch naming,
  commit format, and the release process as one source of truth instead of risking drift
  between two files that both talk about branches.
- **Confirm at risky steps only** (not full-auto, not confirm-every-command) — the agent
  runs branch creation / version bump / tests / PR-to-main without asking, then explicitly
  STOPs before merging to `main`, tagging, or merging back to `develop`.

Implementation: added a "Release flow" section to
`.claude/skills/git-workflow/SKILL.md` with numbered steps and explicit **STOP** points,
plus trigger phrases ("cut a release", "prepare release", "release x.y.z", "hotfix") added
to the skill's frontmatter description. Also wrote `docs/adr/ADR-0002-release-process.md`
(project-visible ADR, distinct from this memory system) documenting the same rationale for
the wider team.

## Why

`.claude/settings.json` grants `Bash(git:*)` with no per-command permission prompt, so the
permission system cannot enforce a pause before a risky git action (merge to main, tag,
push) — the skill's own written instructions are the only thing that can. This is why the
release flow is written as step-gated instructions with explicit STOP markers rather than
just a checklist of commands.

## Outcome

Shipped as PR #2 (`feature/release-flow-skill` → `develop`):
https://github.com/Aibles-Java/feature_flag/pull/2
