# Handoff

*Ephemeral — overwritten by `/save-memory` at the end of each session. Read this first.*

## Current WIP

Gated the GHCR `publish` job behind SonarQube, on branch
**`feature/gate-publish-behind-sonar`** (off `origin/develop`, which already contains the
0024 Sonar-into-CI merge via PR #88).

- `.github/workflows/workflow.yml`: `publish` now `needs: [test, sonar]` with an `if:` that
  uses `!cancelled()` + explicit result checks so it runs on Sonar `success`/`skipped` but
  is blocked on `failure`/`cancelled`. `deploy` inherits the gate via `needs: publish`.
- Committed as `f6afcec` (`ci: gate GHCR publish behind SonarQube`). `actionlint` clean.
- Recorded decision [[0025-gate-publish-behind-sonar]].
- **About to push** — this `/save-memory` satisfies the pre-push memory gate.

## Context to Load

- [[0025-gate-publish-behind-sonar]] — this change + the skipped-dependency `if:` trap.
- [[0024-merge-sonar-into-ci-gated]] — the prior merge (already on develop, PR #88).

## Next steps

1. `git push -u origin feature/gate-publish-behind-sonar` (the branch currently tracks
   `origin/develop`, so push MUST name the branch explicitly — do NOT push to develop).
2. Open a PR → `develop`.
3. Verify on the PR that `publish` still runs after `test`+`sonar`, and confirm on a later
   `v*` tag that `publish`/`deploy` still fire despite `sonar` being skipped.

## Known landmines

- **Unrelated stash still parked:** `stash@{0}` (`loopback-binding docker-compose.prod.yml`)
  belongs on `fix/trivy-action-version`, NOT this branch. `git stash pop` it only after
  checking out `fix/trivy-action-version`.
- Branch `feature/gate-publish-behind-sonar` tracks `origin/develop` (side effect of
  `checkout -b <name> origin/develop`); always push with an explicit branch name.
- `./mvnw test -Dtest='A+B'` is not valid surefire syntax — use `-Dtest='A,B'`.
