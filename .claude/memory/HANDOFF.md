# Handoff

*Ephemeral — overwritten by `/save-memory` at the end of each session. Read this first.*

## Current WIP

**Issue #4** (security test coverage) on branch `feature/issue-4-security-tests`
(→ `develop`). Tests are written and **all 35 pass** (`./mvnw test` → BUILD SUCCESS).
Board card for #4 assigned to @trinhvandat and at **In progress**. Commit / push / PR are
happening this session.

New test files (all under `src/test/java/org/aibles/feature_flag/`):
- `security/JwtTokenProviderTest` (5) — valid / expired / malformed / tampered /
  wrong-secret; claim round-trip.
- `security/JwtAuthenticationFilterTest` (4) — sets auth on valid Bearer; no auth on
  missing / non-Bearer / invalid.
- `security/ApiKeyAuthenticationFilterTest` (3) — 401 problem-detail on missing / unknown
  key; sets `Environment` principal + proceeds on valid key.
- `security/CustomUserDetailsServiceTest` (2) — maps `User`→`UserPrincipal`; throws
  `UsernameNotFoundException` when absent.
- `security/SecurityChainIntegrationTest` (6, `@SpringBootTest`) — both chains reject
  unauth; API key can't hit admin routes, JWT can't hit SDK routes.
- `service/impl/PermissionServiceTest` (12) — OWNER/ADMIN/VIEWER gate on
  org/project/environment + membership + not-found paths.
- `util/ApiKeyGeneratorTest` (2) — 64-char lowercase hex + uniqueness.

Coverage: the six issue-named classes (JwtTokenProvider, JwtAuthenticationFilter,
ApiKeyAuthenticationFilter, CustomUserDetailsService, PermissionService, ApiKeyGenerator)
are all at **100% instruction coverage**. 40 tests total after folding in a `java-reviewer`
pass (strengthened cross-chain proof with real valid JWTs; tightened admin status asserts to
403; added `currentUserId` null/wrong-type + JWT edge cases).

**Spun off issue #10** (assigned to `oanhhkim`, board=Todo): the review + a new integration
test proved a real production defect — a valid JWT for a deleted user throws
`UsernameNotFoundException` out of `JwtAuthenticationFilter` (it runs *before*
`ExceptionTranslationFilter`, so it's never translated) → HTTP 500 instead of 401/403. Per
the user's call this PR stays **test-only**: `SecurityChainIntegrationTest`
.adminValidTokenForDeletedUser_currentlyLeaksException pins the current behaviour; flip it to
`isForbidden()` when #10 is fixed.

## Context to Load

- `conventions/springboot4-security-testing.md` — the two Boot-4 test gotchas hit this
  session (MockMvc setup + deterministic JWT tamper). Read before writing more security
  tests.
- `decisions/0005-issue-workflow-board-and-memory-gate.md` — board script + memory gate.
- `decisions/0004-jacoco-coverage-ratchet-and-ci.md` — the coverage ratchet these tests
  feed; consider bumping `jacoco.line.coverage` above 0.00 in a follow-up now that real
  coverage exists.

## Next steps

- After PR opens: `.claude/scripts/issue-board.sh ready 4`.
- Follow-up worth doing: raise `jacoco.line.coverage` off 0.00 now that the security
  package has real tests (was intentionally 0.00 until coverage landed — see #3).
- Unrelated pre-existing WIP still uncommitted in the tree: `.gitignore` (adds `.omc/`) and
  `docs/ARCHITECTURE.md` (regenerated) — deliberately kept out of the #4 commit; land or
  discard separately.
- Still open from prior sessions: PRs #8, #7, #2, #1 — all → `develop`, unmerged.
