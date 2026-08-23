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

## Cross-branch / open PRs (all three conflict-resolved this session — MERGEABLE + CI green)

- **#43** (issue #27, docker port/non-root) — merged `develop` in; kept #25's readiness HEALTHCHECK
  layered under `USER spring`; compose now passes `APP_JWT_SECRET` (the image bakes the prod profile,
  so it would have crash-looped). Decision **0019**.
- **#58** (issue #31, audit log) — merged `develop` in; **migration renumbered 010 → 011** (#32 took
  010 for refresh-tokens; git did NOT flag it — two different filenames both added); kept develop's
  `@Transactional(readOnly=true)` on `listMembers` (the #52 fix). Decision **0020**.
- **#60** (issue #34, GHCR publish + Trivy) — merged `develop` in; verified the raised
  `jacoco.line.coverage=0.87` still holds after #32 landed (measured 0.8938).
  Decision **0018**.
- Decision numbers across open PRs: 0018 (#60) / 0019 (#43) / 0020 (#58) / **0021 (#35, this
  branch)** — collision-free in any merge order.
- **#53** (issue #30, evaluation cache) — open; its pre-rollout `FlagStateSnapshot` design is what
  satisfies #35's caching bullet. ADR-0004 records the invariant any future cache layer must keep.
- Unanswered review comment on **#58**: "check the warning please" — every CI warning is
  pre-existing on `develop` (verified by diffing against run `30373689296`); the only one worth
  fixing is `HHH90000025 H2Dialect ... specified explicitly` (drop `hibernate.dialect` from
  `application-test.properties`). Awaiting the reviewer's preference.

## Known landmines

- **Windows docs case-collision** (`docs/ARCHITECTURE.md` vs `docs/architecture.md`): while both
  paths are tracked the phantom one is *always* dirty and **`git merge` refuses to start** —
  `git stash` only flips which name is dirty. Fix is `git rm --cached docs/ARCHITECTURE.md`.
  `develop` renamed the uppercase file to `docs/architecture-design-v1.md`; PRs #43 and #58 each
  carry the `git rm --cached` plus an updated `conventions/windows-docs-case-collision.md`. This
  branch is off `develop` so it never had the phantom — do **not** re-update that convention file
  here, it would conflict three ways.
- `./mvnw test -Dtest='A+B'` is not valid surefire syntax — use `-Dtest='A,B'`.
