# Handoff

*Ephemeral — overwritten by `/save-memory` at the end of each session. Read this first.*

## Current WIP

**Issue #30 — evaluation caching** on branch `feature/issue-30-evaluation-caching`.
Implementation + all PR #53 HIGH findings fixed. 188 tests pass, `./mvnw verify` green.
**PR #53 is open.** Memory committed. Branch pushed — next step: move card to Ready For Testing.

### What changed in this session (vs. prior session)

Applied all HIGH findings from PR #53 code review:
- **HIGH-1 (thundering herd):** `EvaluationCacheService.getOrLoad()` added; `EvaluationServiceImpl` uses atomic `cache.get(key, loader)` via it — eliminates double-checked-put race.
- **HIGH-2 (missing env delete eviction):** `EnvironmentServiceImpl.delete()` now calls `evaluationCacheService.evict(id)`.
- **HIGH-3 (actuator metrics exposure):** `application.properties` — reverted to `health,info` only; `metrics` removed.
- **HIGH-4 (pre-commit eviction comment):** Explanatory comment added above `evictAllEnvironmentsForProject()`.
- Tests: rewrote `EvaluationServiceImplTest` with `cacheHit()`/`cacheMiss()` helpers; added `getOrLoad` unit tests to `EvaluationCacheServiceImplTest`; fixed constructor arity in `EnvironmentServiceImplTest` and `FeatureFlagServiceImplTest`.
- `docs/architecture.md` substantially expanded (pre-existing uncommitted change by user, committed here).

### Still open: MEDIUM-2

`EvaluationCacheIntegrationTest.updateState_invalidatesCache_soNextCallHitsDb` calls
`evaluationCacheService.evict()` directly rather than the real `FeatureFlagServiceImpl.updateState()`.
The real flow needs a populated `SecurityContextHolder` (JWT principal for `PermissionService`).
Deferred — the test correctly validates cache invalidation via the service layer's eviction hook,
but the invocation path is abbreviated.

## Context to Load

- `decisions/0015-evaluation-caching.md` — all design choices for the cache implementation.
- `conventions/springboot4-mockito-spy-bean.md` — `@MockitoSpyBean` replaces `@SpyBean`.

## Next steps

1. **Move card to Ready For Testing:** `.claude/scripts/issue-board.sh ready 30`
2. Then pick up code-graph issues (#48 → #50 → #49) or parked items below.

## Parked / cross-branch

- Issues #10, #17 — commit/push/PR/`ready` still pending from earlier sessions.
- Issue #14 (SonarQube) waiting on infra.

## Follow-ups (carry-forward)

- **MEDIUM-2:** Make `EvaluationCacheIntegrationTest.updateState_*` call real `FeatureFlagServiceImpl.updateState()` with a proper security context.
- **#26:** per-IP SDK rate limit for invalid keys; Redis backend for multi-instance.
- **#24:** make `feature_flags.key` H2-safe so SDK eval GET can assert 200 (not just "not 401").
- **Code graph (#48/#49/#50):** ArchUnit Tier-1 gate → Tier-2 custom conditions → CodeGraphContext spike.
- **v2 roadmap:** Redis caching to replace Caffeine for multi-instance evaluation cache.
