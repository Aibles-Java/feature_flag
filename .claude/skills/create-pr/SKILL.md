---
name: create-pr
description: >
  Open a GitHub pull request for feature_flag using this project's fixed
  format (Summary, Related issue, Changes, Test plan, Screenshots, Reviewer
  checklist) instead of a generic PR description. Trigger phrases: "create pr",
  "open pr", "raise pr", "make a pull request", "push and open a PR".
metadata:
  template_version: "1.0.0"
argument-hint: "[ticket-id]"
---

# /create-pr

Opens a pull request for the current branch using this repo's fixed format
(matches `.github/PULL_REQUEST_TEMPLATE.md`, so Claude-authored and human-authored
PRs look the same). See `.claude/skills/git-workflow/SKILL.md` for the underlying
branch/commit conventions this builds on.

## Steps

### 1 — Determine base branch

Read the current branch name (`git branch --show-current`):
- `feature/*` → base is `develop`
- `release/*` or `hotfix/*` → base is `main`
- anything else (e.g. already on `develop`/`main`) → ask the user what base to use;
  do not guess

### 2 — Gather context

- `git log <base>..HEAD --oneline` and `git diff <base>...HEAD` — the full set of
  commits/changes going into this PR (not just the latest commit)
- `git status` — confirm no uncommitted changes are being left out; if there are,
  ask before proceeding
- Ticket ID: use the `$1` argument if passed (e.g. `/create-pr JIRA-123`); otherwise
  look for a ticket-like token in the branch name (e.g. `feature/JIRA-123-slug`); if
  none found, write "N/A" in the Related issue section — never fabricate a ticket ID

### 3 — Draft the PR body

Fill in this exact structure — do not add, remove, or rename sections:

```markdown
## Summary

[1-3 sentences: what changed and why, derived from the commits above]

## Related issue/ticket

[ticket ID/link, or "N/A"]

## Changes

- [bullet per logical change, derived from the commit log/diff]

## Test plan

- [ ] `./mvnw test` passes
- [ ] Manually verified via Swagger UI / SDK call
- [ ] New/updated tests added for the change

[Only if screenshots/curl output/logs are available or the change is user-facing,
keep the section below; otherwise omit it entirely rather than leaving it empty:]

## Screenshots / evidence

[paste evidence]

## Reviewer checklist

- [ ] No breaking changes to `FeatureFlag.key` immutability or SDK response shape
- [ ] If `security/`, JWT config, or `ApiKeyGenerator` touched: security review done
- [ ] If `db/changelog/migrations/` touched: new changeset only, no edits to already-run changesets
- [ ] Base branch is correct (`develop` for features, `main` for hotfixes/releases)
```

Check off (`[x]`) only items you actually verified in this session — never mark a
checklist item done without evidence (e.g. don't check "`./mvnw test` passes"
unless you ran it in this session and it passed).

### 4 — Title

Conventional Commits format: `type(scope): subject` — same rules as
`git-workflow`'s commit format (imperative, ≤72 chars). Use the dominant commit's
subject if the branch has one logical change; otherwise summarize.

### 5 — Push and create

1. Push the branch if not already tracking a remote: `git push -u origin <branch>`
2. Create the PR targeting the base from step 1:
   ```bash
   gh pr create --base <base> --title "<title>" --body "$(cat <<'EOF'
   <body from step 3>
   EOF
   )"
   ```
3. Report the returned PR URL to the user.

## Boundaries

- Never force-push or rewrite history to "clean up" a PR — see `git-workflow`
  boundaries.
- Never push to or open a PR against `main` directly from a `feature/*` branch —
  base must be `develop`.
- Do not open the PR without showing the drafted title+body to the user first if
  this is the first PR created in the session (subsequent PRs in the same session
  can skip re-confirming the format, not the content).
