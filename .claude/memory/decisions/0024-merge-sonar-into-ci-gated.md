# 0024 — Gate SonarQube behind the CI build (merge sonar.yml into workflow.yml)

**Branch:** `feature/merge-sonar-into-ci` (off `develop`). Commit `f523d07`.

## What was decided

Sonar was a **standalone** workflow (`.github/workflows/sonar.yml`, see
[[0018-sonarqube-ci-self-hosted-runner]]) that ran **in parallel** with CI/CD
(`workflow.yml`) and fired even when the CI build was red. Merged it into
`workflow.yml` as a `sonar` job with **`needs: test`**, so it only starts once the
`test` job (Build & test, Java 21) is green. Deleted `sonar.yml`.

## Why / key mechanics

- **Gating, not efficiency.** The build already ran twice before (CI `test` +
  Sonar's own `./mvnw verify`), just concurrently. After the merge it is the same
  total work but **sequential-and-gated** — a red build no longer triggers a
  redundant Sonar run.
- **Job stays self-contained** (own full-history `fetch-depth: 0` checkout, JDK 21,
  `~/.sonar/cache`, own `./mvnw verify …:sonar`) **on purpose**: it runs on
  `self-hosted` (SonarQube server is on the internal network) while `test` runs on
  `ubuntu-latest`. The compiled classes + JaCoCo `jacoco.xml` Sonar needs live on
  the `test` runner's filesystem, not the self-hosted one — sharing them across
  runners (artifact upload/download) was rejected as more fragile than just
  rebuilding. Net wall-clock cost: Sonar now waits for `test` first.
- **Triggers preserved exactly** via `if:` (the merged workflow's `on:` is broader
  than the old sonar.yml's): run on **push to `develop`/`main`** + **non-fork PRs
  into `develop`**; never on tags, never on PRs to `main`, skipped for fork PRs
  (no `SONAR_TOKEN`). `github.base_ref == 'develop'` gates the PR case;
  `github.ref == 'refs/heads/develop'|'main'` gates the push case (excludes tags).
- **Job name unchanged** (`SonarQube analysis`) so the branch-protection required
  status-check context keeps matching. The *workflow* name it reports under changes
  `SonarQube` → `CI/CD` — verify required-checks config if it keys on workflow name.
- **NOT a deploy gate.** `publish`/`deploy` still `needs: test` only, matching the
  prior behavior where Sonar was fully independent of the deploy path. To make a
  Sonar/Quality-Gate failure block publish, add `sonar` to `publish`'s `needs`.
- **Concurrency change:** Sonar now inherits `workflow.yml`'s group
  (`cancel-in-progress: false` on push, see [[0022-cd-deploy-prod-self-hosted-compose]]),
  vs the old standalone `cancel-in-progress: true`. Rapid `develop` pushes now
  serialize the whole CI/CD run (incl. Sonar) rather than cancelling superseded Sonar
  runs — acceptable, and required for deploy safety.

## Pattern

Jobs that must be *ordered* across different runner types don't have to share
artifacts — a self-contained `needs:`-gated job that rebuilds is often simpler and
more robust than cross-runner artifact passing, when the rebuild cost is already
being paid.
