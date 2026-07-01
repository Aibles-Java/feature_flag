# Handoff

*Ephemeral — overwritten by `/save-memory` at the end of each session. Read this first.*

## Current WIP

Nothing in progress. **PR #7** (`feature/jacoco-ci-coverage-gate` → `develop`) is open,
CI green, awaiting review/merge: https://github.com/Aibles-Java/feature_flag/pull/7

It closes issue #3 and contains:
- `jacoco-maven-plugin` in `pom.xml` (prepare-agent + report + a ratchet `check`, all on
  `verify`); floor is a `jacoco.line.coverage` property currently `0.00` (non-blocking)
- `.github/workflows/ci.yml` — runs `./mvnw verify` on PRs to `develop`/`main`; JDK 21;
  uploads JaCoCo report artifact. Verified green on PR #7 itself.
- Removed a duplicate `spring-security-test` dependency in `pom.xml`

Still open from prior sessions: PR #1 (`feature/claude-harness-setup`) and PR #2
(`feature/release-flow-skill`), both → `develop`, unmerged.

## Context to Load

- `decisions/0004-jacoco-coverage-ratchet-and-ci.md` — before raising the coverage
  threshold or touching JaCoCo / `ci.yml`; explains why the floor starts at 0.00
- `decisions/0003-release-flow-in-git-workflow-skill.md` — if asked about the release process
- `conventions/stop-hook-nudge-needs-commit-tracking.md` — before touching `remind-save.sh`
- `decisions/0001-claude-code-harness-setup.md` — harness config choices (incl. 80% target)

## Next steps

- Review/merge PR #7 (JaCoCo + CI) → `develop`
- Raise `jacoco.line.coverage` in `pom.xml` as real tests land (companion test-coverage issues)
- Review/merge the still-open PR #1 and PR #2
