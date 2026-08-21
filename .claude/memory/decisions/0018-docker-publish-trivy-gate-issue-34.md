# 0018 — GHCR publish + Trivy gate: scan-before-push, and don't trust `is_default_branch`

**Issue #34** (branch `feature/issue-34-docker-publish-scanning`). CI only ran tests —
no released artifact, no image/dependency scanning, no automated dependency updates.
Added a `publish` job to the existing `.github/workflows/ci.yml` rather than a new
workflow file, so there's one source of truth for "what runs when."

## Decision 1: build → scan (local, unpushed) → push, never scan-after-push

`publish` builds the image with `docker/build-push-action` using `push: false, load: true`
(loads into the runner's local Docker daemon, nothing touches GHCR yet), scans that local
image with Trivy (`severity: HIGH,CRITICAL`, `exit-code: 1`, `.trivyignore` allowlist), and
only on a clean scan does a second `build-push-action` step (this time `push: true`) actually
publish — reusing the `type=gha` cache from the first build so the second build is a
cache-hit, not a full rebuild. No `continue-on-error` anywhere in the chain, so a failing
scan halts the job before login/push ever run. A HIGH/CRITICAL image never reaches GHCR,
not even transiently for someone to `docker pull` it mid-scan.

Trivy scans by the **first computed tag** (`fromJSON(steps.meta.outputs.json).tags[0]`),
not `docker/build-push-action`'s `imageid` output. `imageid` looked plausible (it's a valid
single-platform output) but no first-party trivy-action doc/example confirms scanning a bare
`sha256:...` image ID is reliable — scanning by tag is the documented, standard pattern and
the local daemon already has that tag from the `load: true` build. A code-review pass flagged
this as worth the safer choice even though it couldn't confirm `imageid` would actually fail.

## Decision 2: tag `latest` on an explicit ref check, not `{{is_default_branch}}`

**This was a real bug caught by code review before merge, not a hypothetical.** The first
draft used `docker/metadata-action`'s `type=raw,value=latest,enable={{is_default_branch}}`,
which reads natural ("latest on the default branch") but `{{is_default_branch}}` compares
against **this repo's actual configured GitHub default branch** — which is `develop`
(Gitflow), not `main`. The `publish` job's `if:` only ever runs on a push to `main` or a
`vX.Y.Z` tag — it never runs on `develop` — so `is_default_branch` is `false` in every
`publish` run that ever executes. Consequence: `latest` would never be applied, and worse, a
bare push to `main` with no tag in the same push would compute an **empty** tag list →
`build-push-action --push` with no `-t` fails outright ("tag is needed when pushing to a
registry"). Every `publish` run triggered by a plain `main` push would have failed.

**Fix:** `type=raw,value=latest,enable=${{ github.ref == 'refs/heads/main' }}` — an explicit
GitHub Actions expression tied to the same ref the job's own `if:` already gates on, not a
metadata-action template variable coupled to a GitHub repo setting that doesn't match this
repo's branching model.

**Lesson:** any repo using Gitflow (or any non-`main` GitHub default branch) must not use
`{{is_default_branch}}`/`{{is_default_branch}}`-style template vars in `docker/metadata-action`
without first checking `gh repo view --json defaultBranchRef` — the semantic gap between
"GitHub's configured default branch" and "the branch we publish `latest` from" is invisible
until a `main` push actually runs and fails.

## Decision 3: scope strictly to the issue text — no SARIF upload, no Docker-ecosystem Dependabot

The issue only asks for a pass/fail Trivy gate and Dependabot for Maven + GitHub Actions.
Deliberately did not add SARIF upload to the GitHub Security tab (would need an extra
`security-events: write` permission on the job) or a `docker`-ecosystem Dependabot entry for
the base image — both reasonable follow-ups, left for a future issue if wanted, per explicit
human instruction to stick to the task description as written.

## Also in this PR: coverage ratchet floor bump (acceptance criterion 4)

`jacoco.line.coverage` was already at `0.83` on `develop` (not `0.00` as the issue text says —
that acceptance criterion had already partially landed in an earlier session), but the
comment's baseline (84.8%) was stale: `coverage-floor.sh` measured actual current coverage at
**0.89**. Bumped the floor to **0.87** (current − 2% churn margin, same convention as every
prior ratchet bump) and corrected the comment. `./mvnw verify` confirmed green at the new floor.

Related: [[second-springboottest-context-shared-h2]] (H2 test convention, unaffected by this
change — this PR touches no Java source, only CI/CD + `pom.xml` properties).
