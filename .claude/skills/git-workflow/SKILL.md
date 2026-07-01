---
name: git-workflow
description: >
  feature_flag git conventions — commit message format, branch naming,
  PR flow, and the release/hotfix process. Apply when committing, branching,
  opening a pull request, or cutting a release. Trigger phrases: "commit",
  "create branch", "open PR", "git workflow", "cut a release",
  "prepare release", "release x.y.z", "hotfix".
metadata:
  template_version: "2.5.0"
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

## Release flow

Trigger: the user explicitly asks to cut/prepare a release or start a
hotfix. Never initiate this proactively, even if `develop` looks
shippable — release timing is a product decision, not the agent's call.

### Cutting a release (do without asking)

1. Branch `release/<version>` from `develop`.
2. Bump `pom.xml` version (drop `-SNAPSHOT`), commit as
   `chore(release): bump version to <version>`.
3. Run `./mvnw test`.
4. Open PR: `release/<version>` → `main`.

**STOP here.** Report the PR link and test results. Wait for explicit
go-ahead before continuing — merging into `main` is not reversible the
way a feature PR is.

### Shipping (only after explicit go-ahead)

5. Merge the PR into `main`.
6. Tag: `git tag -a v<version> -m "Release <version>"`, push the tag.
7. Open PR: `main` → `develop` (merge-back).
8. Bump `develop`'s `pom.xml` to `<next-version>-SNAPSHOT`.

**STOP here.** Report the tag and merge-back PR link. Do not merge the
merge-back PR without a separate confirmation.

### Hotfix (branches from `main`, not `develop`)

1. Branch `hotfix/<slug>` from `main`.
2. Fix, bump the patch version, run tests, open PR to `main`.

**STOP.** Wait for go-ahead.

3. On go-ahead: merge to `main`, tag, then open PR(s) merging the fix
   into `develop` (and into any in-flight `release/*` branch).

**STOP** before merging those merge-back PR(s).

### Rules

- If the release or hotfix touches `security/`, JWT config,
  `db/changelog/migrations/`, or `ApiKeyGenerator`, run the
  security-review skill before opening the PR to `main`.
- Never skip the merge-back to `develop` — it's the most common cause
  of a bug fixed in a release reappearing in the next one.
- Never `git push --force` or overwrite an existing tag on `main`.

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
