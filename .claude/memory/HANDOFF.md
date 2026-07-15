# Handoff

*Ephemeral — overwritten by `/save-memory` at the end of each session. Read this first.*

## Current WIP

**Issue #30 — evaluation caching** on branch `feature/issue-30-evaluation-caching`.
All PR #53 review findings addressed (both from trinhvandat). 224 tests pass, `./mvnw verify` green.
**PR #53 is open.** Branch is ahead of origin — push needed (HTTPS auth required, do it manually).

### What changed in this session

Addressed trinhvandat's PR #53 review (2 findings):
- **Finding 1 (correctness — pre-commit eviction race):** Added `EvaluationCacheService.evictAfterCommit()`
  using `TransactionSynchronizationManager.registerSynchronization(...afterCommit)`. Replaced all
  `evict()` calls in `FeatureFlagServiceImpl` and `EnvironmentServiceImpl.delete()` with
  `evictAfterCommit()`. Removed stale "intentionally safe" comment.
- **Finding 2 (MEDIUM-2 — untested eviction wiring):**
  - Unit tests: added `verify(evaluationCacheService).evictAfterCommit(...)` to create/archive/
    unarchive/updateState in `FeatureFlagServiceImplTest`; added env stub to archive/unarchive tests.
  - Integration test: dropped `@Transactional` so `updateState()` commits its own txn and the
    afterCommit hook fires; added `@MockitoBean PermissionService` to skip auth setup;
    test now drives through real `FeatureFlagService.updateState()`.
  - Added `evictAfterCommit_withNoActiveTransaction_evictsImmediately` to `EvaluationCacheServiceImplTest`.
- Merged `origin/develop` (PRs #39, #54, #55 — secrets externalization, structured logging, db-reset).
  Only `.claude/memory/` conflicts; resolved by union.

## Context to Load

- `decisions/0015-evaluation-caching.md` — all design choices for the cache implementation.
- `conventions/springboot4-mockito-spy-bean.md` — `@MockitoSpyBean` replaces `@SpyBean`.

## Next steps

1. **Push branch:** `git push origin feature/issue-30-evaluation-caching` (needs GitHub HTTPS/SSH auth).
2. **Reply on PR #53** that both findings are addressed (commit `395e9dc`).
3. **Move card to Ready For Testing:** `.claude/scripts/issue-board.sh ready 30`
4. Then pick up code-graph issues (#48 → #50 → #49) or parked items below.

## Parked / cross-branch

- Issues #10, #17 — commit/push/PR/`ready` still pending from earlier sessions.
- Issue #14 (SonarQube) waiting on infra.

## Follow-ups (carry-forward)

- **#26:** per-IP SDK rate limit for invalid keys; Redis backend for multi-instance.
- **#24:** make `feature_flags.key` H2-safe so SDK eval GET can assert 200 (not just "not 401").
- **Code graph (#48/#49/#50):** ArchUnit Tier-1 gate → Tier-2 custom conditions → CodeGraphContext spike.
- **v2 roadmap:** Redis caching to replace Caffeine for multi-instance evaluation cache.
