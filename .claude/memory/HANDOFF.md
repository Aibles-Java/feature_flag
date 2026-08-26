# Handoff

*Ephemeral — overwritten by `/save-memory` at the end of each session. Read this first.*

## Current WIP

Merged the standalone SonarQube workflow into CI/CD on branch
**`feature/merge-sonar-into-ci`** (off `develop`).

- `.github/workflows/workflow.yml`: added a `sonar` job with `needs: test` (job name
  `SonarQube analysis`, self-contained, self-hosted). `.github/workflows/sonar.yml`
  deleted. Validated with `actionlint` (no errors).
- Committed as `f523d07` (`ci: gate SonarQube analysis behind CI build`). The commit
  was amended once — the first attempt captured only the `sonar.yml` deletion because
  a `git add` aborted on a pathspec error; both files are now in the commit
  (`2 files changed, 56 insertions(+), 52 deletions(-)`).
- **About to push** — this `/save-memory` satisfies the pre-push memory gate.

## Context to Load

- [[0024-merge-sonar-into-ci-gated]] — the merge decision, why the job stays
  self-contained, trigger/branch-protection/concurrency implications.
- [[0018-sonarqube-ci-self-hosted-runner]] — the original standalone Sonar setup this
  supersedes.

## Next steps

1. `git push -u origin feature/merge-sonar-into-ci` (memory gate now satisfied).
2. Open a PR → `develop`. **Base is stale**: this branch is off a local `develop` that
   was ~11 commits behind `origin/develop` — consider `git fetch` + rebase onto
   `origin/develop` before/after opening the PR so CI runs against current state.
3. On the PR, confirm the `SonarQube analysis` check still appears and runs **after**
   `Build & test (Java 21)`, and that branch-protection required checks still match
   (workflow name it reports under changed `SonarQube` → `CI/CD`).
4. Decide whether Sonar should also gate `publish`/`deploy` (currently it does NOT —
   they `needs: test` only). If yes, add `sonar` to `publish`'s `needs`.

## Known landmines

- **Unrelated stash still parked:** `stash@{0}` (`loopback-binding docker-compose.prod.yml`)
  belongs on `fix/trivy-action-version`, NOT this branch. `git stash pop` it only after
  checking out `fix/trivy-action-version`.
- **Windows docs case-collision** (`docs/ARCHITECTURE.md` vs `docs/architecture.md`):
  `develop` already renamed the uppercase file; this branch is off `develop` so it should
  be clean — do not re-touch `conventions/windows-docs-case-collision.md`.
- `./mvnw test -Dtest='A+B'` is not valid surefire syntax — use `-Dtest='A,B'`.
