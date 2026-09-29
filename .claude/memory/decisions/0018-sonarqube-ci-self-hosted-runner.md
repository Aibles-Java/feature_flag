# 0018 — SonarQube analysis in CI on a self-hosted runner

**Date:** 2026-08-23
**Status:** Accepted

## What

Added `.github/workflows/sonar.yml` — a `SonarQube` workflow that runs
`./mvnw verify org.sonarsource.scanner.maven:sonar-maven-plugin:sonar` on a
**self-hosted** GitHub Actions runner. Triggers on push to `develop`/`main` and
PRs targeting `develop`.

## Key design choices (why the workflow looks the way it does)

- **`runs-on: self-hosted`** — the SonarQube server is on the internal network;
  a self-hosted runner (registered on the same server) can reach `SONAR_HOST_URL`
  where GitHub-hosted runners can't.
- **No `.env` file, no "GitHub↔Sonar link".** The workflow *is* the integration:
  the scanner pushes results to the server. Only two GitHub **repository secrets**
  are needed: `SONAR_TOKEN` and `SONAR_HOST_URL`. Never commit these to a `.env`.
- **Dropped `-Dsonar.host.url` and `-Dsonar.token` flags.** `sonar-maven-plugin`
  auto-reads the `SONAR_HOST_URL` / `SONAR_TOKEN` env vars, so the `-D` flags were
  redundant. They stay in `env:` only. Kept `-Dsonar.projectKey=feature_flag`.
- **`permissions: contents: read`** — least-privilege token.
- **`timeout-minutes: 20`** — a hung scan must not hold the single self-hosted
  runner indefinitely (1 runner = 1 concurrent job).
- **Fork-PR guard** (`if: push || pull_request.head.repo == repo`) — fork PRs get
  no secrets *and* self-hosted runners must never execute untrusted fork code.
  Skip instead of failing red. Private repo, so low risk today, but defense-in-depth.
- **`fetch-depth: 0`** — full history for accurate new-code / blame detection.
- **Sonar package cache** (`~/.sonar/cache`) + `setup-java` maven cache for speed.

## Runner operational notes

- Registered via Settings → Actions → Runners → New self-hosted runner (Linux x64).
  Run under a dedicated non-root user (`gh-runner`); install as a service
  (`./svc.sh install && ./svc.sh start`) so it survives reboot — `./run.sh` only
  lives as long as the terminal.
- Server prerequisites: reachable `SONAR_HOST_URL`, internet for `setup-java` JDK
  download, Docker (add `gh-runner` to `docker` group) if integration tests spin up
  Postgres.

## Coverage caveat (not done here)

SonarQube needs a JaCoCo `jacoco.xml` report to score coverage; that lives in
`pom.xml`, not this workflow. Not verified in this session — see [[0012-harness-guards-spotless-coverage]].
