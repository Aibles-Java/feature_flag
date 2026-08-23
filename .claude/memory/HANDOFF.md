# Handoff

*Ephemeral — overwritten by `/save-memory` at the end of each session. Read this first.*

## Current WIP

SonarQube CI on a self-hosted runner.

- `.github/workflows/sonar.yml` — refactored + committed as `4f2b690` on branch
  **`feature/sona-ci`** (local `develop` reset back to `origin/develop`, clean).
- A self-hosted GitHub Actions runner is **registered and online (Idle)**, currently
  running via `./run.sh` in a foreground terminal (NOT yet installed as a service).
- Not yet pushed — was blocked by the pre-push memory gate; this `/save-memory` run
  unblocks it.

## Context to Load

- [[0018-sonarqube-ci-self-hosted-runner]] — the workflow design + runner ops notes.

## Next steps

1. Commit this memory + push `feature/sona-ci` (memory gate now satisfied).
2. Open a PR `feature/sona-ci` → `develop`; the `pull_request` trigger runs the
   SonarQube job. Watch: `run.sh` terminal should print `Running job: sonar`; Actions
   tab should go green.
3. If green: `Ctrl+C` the `run.sh` terminal → install runner as a service
   (`sudo ./svc.sh install gh-runner && sudo ./svc.sh start`) so it survives reboot.
4. Confirm the two repo secrets exist: `SONAR_TOKEN`, `SONAR_HOST_URL`.
5. Follow-up (separate): verify `pom.xml` emits JaCoCo `jacoco.xml` so Sonar scores
   coverage — not done in this session.
