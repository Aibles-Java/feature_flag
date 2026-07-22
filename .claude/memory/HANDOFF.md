# Handoff

*Ephemeral — overwritten by `/save-memory` at the end of each session. Read this first.*

## Current WIP

**Issue #34** (publish versioned Docker image with vulnerability scanning) on branch
`feature/issue-34-docker-publish-scanning` (→ `develop`). **Implementation complete,
committed (`8e82751`), code-reviewed (one critical finding, fixed before this commit).
Not yet pushed / no PR yet.**

What changed (CI/CD + `pom.xml` only — no Java source touched):
- `.github/workflows/ci.yml`: added `tags: ['v*']` trigger; new `publish` job
  (`needs: test`, only on push to `main` or a `vX.Y.Z` tag, never PRs) that builds the
  image locally, Trivy-scans it (HIGH/CRITICAL gate, `.trivyignore` allowlist), and only
  pushes to GHCR on a clean scan, reusing the GHA layer cache.
- `.github/dependabot.yml` (new): maven + github-actions, weekly.
- `.dockerignore` (new).
- `.trivyignore` (new, empty allowlist template).
- `pom.xml`: `jacoco.line.coverage` ratchet floor `0.83 → 0.87` (measured actual 0.89).

`./mvnw verify` green at the new floor. See
`decisions/0018-docker-publish-trivy-gate-issue-34.md` for the two real design gotchas
(scan-by-tag not imageid; `is_default_branch` doesn't mean what it sounds like on a
Gitflow repo whose GitHub default branch is `develop`, not `main` — this was a genuine
bug caught by code review, not a hypothetical, and is fixed in the commit).

## Next steps (issue #34 / no PR yet)
1. Push `feature/issue-34-docker-publish-scanning` (memory now staged alongside code,
   satisfies the memory gate). **gh is at `C:\Users\ACER\AppData\Local\gh-cli\bin`
   (not on PATH)** — prepend it; see
   `~/.claude/projects/.../memory/gh-cli-off-path-location.md`.
2. `create-pr` skill, base `develop`, `Closes #34`.
3. `.claude/scripts/issue-board.sh ready 34` right after the PR opens.
4. Note in the PR body: this PR does NOT add SARIF upload to the GitHub Security tab or
   a Docker-ecosystem Dependabot entry — deliberately out of scope per the issue text
   (see decision 3 in 0018), flagged as a possible follow-up.

## Also this session (separate, already shipped — not part of #34)
- Reviewed PR #59 (issue #32, refresh tokens) as a bug scanner: **no correctness bugs
  found** in `RefreshTokenServiceImpl`/`RefreshTokenFamilyRevoker`/`AuthServiceImpl`/
  `GlobalExceptionHandler` — the two subtle transaction/deadlock bugs documented in
  `decisions/0017-refresh-token-family-revoke-transaction-semantics.md` (on that branch,
  not yet on `develop`) were already correctly fixed.
- PR #59's CI failed on `spotless:check` (a comment line too long in
  `RefreshTokenServiceImplTest.java`, added by a later commit `e3987db`). Fixed with
  `mvnw spotless:apply`, pushed as `6192c78` — CI green again. Issue #32's board card was
  already at **Ready For Testing**; nothing further needed there.

## Context to Load
- `decisions/0018-docker-publish-trivy-gate-issue-34.md` — read before touching
  `.github/workflows/ci.yml` again (the `is_default_branch` gotcha applies to any future
  workflow using `docker/metadata-action` on this repo).
- `decisions/0017-pagination-admin-list-endpoints.md` + `docs/adr/ADR-0003-pagination-strategy.md`.

## Known repo issue (pre-existing, not #34)
- **`docs/` case collision**: `docs/ARCHITECTURE.md` vs `docs/architecture.md` (differ
  only by case) → git perpetually reports one modified on this Windows FS. Kept out of
  every commit. Fix on a case-sensitive box by deleting one path. (Note: PR #59's branch
  separately renamed `docs/ARCHITECTURE.md` → `docs/architecture-design-v1.md` to clear
  this — once #59 merges to `develop` this note may become stale, re-check then.)

## Follow-ups (carried over)
- **#28** JSON logging — PR **#54** open (mergeable), board Ready For Testing.
- **#31** audit log — depends on #33's paginated read endpoint; JSONB-on-H2 risk.
- **#32** refresh tokens — PR **#59** open, CI green, board Ready For Testing.
- **Bug #52**: `GET /organisations/{id}/members` → 500, lazy `getUser().getEmail()`
  outside a transaction; needs `@Transactional(readOnly=true)`/JOIN FETCH. Also reports
  `register returns 201-empty` — separate, needs its own look.
- **Codegraph #48/#49/#50** on board; #48 (Tier-1 ArchUnit) next greenfield pick.
- Two `decisions/0012-*` files still collide, and (once #59 merges) two `0017-*` files —
  renumber later.
- **#25:** Dockerfile HEALTHCHECK readiness→liveness? DB-down readiness→503 test.
- **#26:** per-IP SDK limit for invalid keys; Redis backend for multi-instance.
- **#24:** make `feature_flags.key` H2-safe so SDK eval can be tested for a real 200.
- **Possible follow-up (not filed):** SARIF upload for Trivy results to the GitHub
  Security tab, and a `docker`-ecosystem Dependabot entry for the base image — both
  deliberately left out of #34's scope (see decision 3 in 0018).
