# 0006 — Self-hosted SonarQube for the coverage board (issue #14)

**Date:** 2026-07-02 · **Issue:** #14 (ci: publish a viewable code-coverage dashboard)

## Decision

Issue #14's coverage dashboard will be backed by a **self-hosted SonarQube server**,
not Codecov (the issue body's original "Option A") and not SonarQube Cloud.
The human decided this in-terminal; recorded on the card:
<https://github.com/Aibles-Java/feature_flag/issues/14#issuecomment-4865687139>

- The SonarQube **server infra lives in a separate repository** (not yet created).
  This repo only wires CI analysis + the quality gate against it.
- Rationale: the team wants the quality gate to **block merges** (not just display
  coverage) and prefers self-hosting over a third-party SaaS.

## Alternatives considered

- **Codecov** (issue's recommendation): free for this public repo, lightest wiring,
  but coverage-only and SaaS.
- **SonarQube Cloud (SonarCloud)**: free for public repos, zero ops, same quality-gate
  model — rejected in favor of self-hosting.

## Implementation plan (agreed, not yet started)

Work splits into a **server-independent slice** (doable now) and a **server-dependent
remainder** (blocked on the infra repo):

Doable before the server exists — branch `feature/issue-14-sonarqube-coverage-board`:
1. `ci.yml`: `mvn verify sonar:sonar` step gated on `SONAR_HOST_URL`/`SONAR_TOKEN`
   secrets being present (expose secret as env, skip step when empty) so CI stays
   green until the server exists.
2. `pom.xml`: `sonar.projectKey` etc. + `sonar.coverage.jacoco.xmlReportPaths=`
   `target/site/jacoco/jacoco.xml` (the report goal already produces this on `verify`).
3. Validate the whole flow against a throwaway local `docker run sonarqube:community`.
4. Raise the JaCoCo ratchet (`jacoco.line.coverage` 0.00 → ~0.25; coverage is ~26%)
   so merge blocking starts now, independent of SonarQube.
5. Job-summary coverage table from `jacoco.csv` (issue's Option C) as interim visibility.
6. Create `README.md` — **the repo has no README**; the issue's badge criterion
   assumes one. Sonar badge slot stays placeholder until the server URL is known.

Blocked on the infra repo:
- Dashboard, live badge (badge URLs are served by the instance), PR decoration.
- Constraint to pass to the infra work: the server must be **reachable from GitHub
  Actions runners** (public URL/tunnel) and needs its GitHub ALM integration
  configured for PR decoration; a purely internal instance won't work with hosted runners.
- Then: add the two repo secrets, verify the gate's status check, require it in
  branch protection, tick #14's acceptance boxes.

## Notes

- #14's issue body still describes the Codecov plan; update it (or comment) when
  implementation starts — body edits by the agent are classifier-blocked, see
  [[decision-comments-cross-issue-blocked]].
- Keep the pom ratchet even after SonarQube lands (offline safety net); the Sonar
  gate's "80% on new code" model is the path to CLAUDE.md's 80% target.
