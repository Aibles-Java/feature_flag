# 0002 — Custom PR template + create-pr skill

**Date:** 2026-07-01

## Decision

Researched existing options for "raise a PR with a fixed format" before building anything.
Found: Claude Code's built-in PR-creation instructions and every marketplace/community
option (`commit-commands` plugin, various `/pr` slash commands) all just draft a *generic*
"reasonable" description — none enforce a specific, fixed template. Built two project-local
artifacts instead of adopting a marketplace plugin:

- `.github/PULL_REQUEST_TEMPLATE.md` — native GitHub template (works for human-authored PRs too)
- `.claude/skills/create-pr/SKILL.md` — Claude skill that fills the exact same template

**Format sections (fixed, do not rename/reorder):** Summary, Related issue/ticket, Changes,
Test plan, Screenshots/evidence (omit if not applicable), Reviewer checklist.

**Base-branch logic:** `feature/*` → `develop`, `release/*`/`hotfix/*` → `main` (matches the
gitflow decision in [[0001-claude-code-harness-setup]]).

## Why

The user specifically wanted a *consistent format*, not just "a PR description" — generic
tools don't guarantee that. Keeping it as a repo-local skill (like `git-workflow`) means the
format travels with the repo and is visible/editable by the whole team, rather than being
personal-session behavior.

## Gotcha discovered

`gh` (GitHub CLI) is **not installed by default** in this environment. The `create-pr` skill's
final step (`gh pr create`) requires the user to install and `gh auth login` themselves first
— this is an interactive/credentialed step Claude cannot do on the user's behalf. Once
authenticated, `gh pr create --base <branch> --title ... --body ...` works normally.

## Outcome

First real use: PR #1 (`feature/claude-harness-setup` → `develop`) —
https://github.com/Aibles-Java/feature_flag/pull/1 — created successfully using this format
after the user installed and authenticated `gh`.
