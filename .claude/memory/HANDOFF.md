# Handoff

*Ephemeral — overwritten by `/save-memory` at the end of each session. Read this first.*

## Current WIP

**Issue #30 — evaluation caching** on branch `feature/issue-30-evaluation-caching`.
Implementation complete, all 186 tests pass, `./mvnw verify` green (Spotless + JaCoCo ≥83%).
**PR not yet opened.**

### What was built this session
- `FlagStateSnapshot` record + `EvaluationCacheService` + `EvaluationCacheServiceImpl` (Caffeine cache)
- `EvaluationCacheProperties` + `EvaluationCacheConfig` (Caffeine bean, Micrometer gauges, ShallowEtagHeaderFilter)
- `EvaluationServiceImpl` rewritten with cache-aside (removed `FeatureFlagRepository` dep)
- `FeatureFlagServiceImpl` — cache eviction wired on create/archive/unarchive/updateState
- `application.properties` — `app.evaluation-cache.*` config, `metrics` added to actuator exposure
- Tests: `EvaluationCacheServiceImplTest` (5), updated `EvaluationServiceImplTest` (7), `EvaluationCacheIntegrationTest` (4)

## Context to Load

- `decisions/0015-evaluation-caching.md` — all design choices for the cache implementation.
- `conventions/springboot4-mockito-spy-bean.md` — `@MockitoSpyBean` replaces `@SpyBean`.

## Next steps

1. **Open PR** via `create-pr` skill. Base: `develop`. Reference `Closes #30`.
2. **Move card** to Ready For Testing: `.claude/scripts/issue-board.sh ready 30`.
3. Then pick up code-graph issues (#48 → #50 → #49) or parked items below.

## Parked / cross-branch

- `docs/architecture.md` — large uncommitted change by oanhhkim, unrelated to issue #30.
- Issues #10, #17 — commit/push/PR/`ready` still pending from earlier sessions.
- Issue #14 (SonarQube) waiting on infra.

## Follow-ups (carry-forward)

- **#26:** per-IP SDK rate limit for invalid keys; Redis backend for multi-instance.
- **#24:** make `feature_flags.key` H2-safe so SDK eval GET can assert 200 (not just "not 401").
- **Code graph (#48/#49/#50):** ArchUnit Tier-1 gate → Tier-2 custom conditions → CodeGraphContext spike.
- **v2 roadmap:** Redis caching to replace Caffeine for multi-instance evaluation cache.
