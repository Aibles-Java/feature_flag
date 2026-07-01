---
name: git-workflow
description: >
  feature_flag git conventions — commit message format, branch naming,
  and PR flow. Apply when committing, branching, or opening a pull request.
  Trigger phrases: "commit", "create branch", "open PR", "git workflow".
metadata:
  template_version: "2.4.0"
---

# Git Workflow — feature_flag

Project-specific git rules. Follow these for every commit, branch, and PR.
This is not a git tutorial — it encodes the conventions chosen for this repo.

## Commits

Format: `type(scope): subject`
- Types: feat, fix, chore, docs, refactor, test, perf, ci
- Subject: imperative, ≤ 72 chars, no trailing period
- Body (optional): explain *why*, not *what*. Wrap at 72 columns.
- Breaking change: add `!` after type/scope, or a `BREAKING CHANGE:` footer.

Examples:
- `feat(auth): add refresh-token rotation`
- `fix(api): handle null cursor in pagination`

## Branches

- `feature/<short-slug>`  → merges into `develop`
- `release/<version>`     → merges into `main` and back into `develop`
- `hotfix/<short-slug>`   → branches from `main`, merges into `main` + `develop`
- Default working branch: `develop`. Never commit directly to `main`.

## Pull Requests

- PR title = the commit subject (or the dominant change if multiple commits).
- Body: summarize *what changed and why*, derived from the commit history
  (`git log <base>..HEAD`).
- Include a short test plan / verification note.
- Keep PRs scoped to one logical change.

## Boundaries

- Do not force-push to shared branches (main, develop).
- Do not amend or rebase commits already pushed to a shared branch.
- Do not commit secrets, build artifacts, or files matching `.gitignore`.
