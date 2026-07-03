# Handoff

*Ephemeral — overwritten by `/save-memory` at the end of each session. Read this first.*

## Current WIP

**Issue #10** (JWT 500 → 403 for deleted user) on branch `feature/issue-10-jwt-deleted-user-500`
(→ `develop`). Implementation complete and verified:

- `security/JwtAuthenticationFilter.java` — wrapped `getEmailFromToken` + `loadUserByUsername`
  in try/catch: `UsernameNotFoundException` (log.warn, fall through unauthenticated → 403)
  + `JwtException` (log.debug, TOCTOU guard on expiry window). Added `@Slf4j`.
- `SecurityChainIntegrationTest.java` — flipped pinned-defect test to
  `adminValidTokenForDeletedUser_returnsForbidden` asserting `status().isForbidden()`;
  removed `UsernameNotFoundException` + `assertThatThrownBy` imports.
- `JwtAuthenticationFilterTest.java` — added `doesNotAuthenticateWhenUserNoLongerExists`
  unit test covering the catch path.

All 41 tests pass. Code review done (two IMPORTANT issues fixed: log level + JwtException TOCTOU).

**Remaining:** commit, push, open PR (`Closes #10`), `issue-board.sh ready 10` after PR opens.
Also: `issue-board.sh start 10` board-move is pending (needs `project` gh scope — user needs
to run `gh auth refresh -h github.com -s project` interactively first).

## Context to Load

- `conventions/jwt-filter-catch-scope.md` — catch scope + log level rules for JWT filters.

## Next steps

1. `gh auth refresh -h github.com -s project` (run interactively) → then `.claude/scripts/issue-board.sh start 10`
2. Commit + push `feature/issue-10-jwt-deleted-user-500`
3. Open PR with `create-pr` skill (`Closes #10`)
4. `.claude/scripts/issue-board.sh ready 10`

**Parked from previous sessions:**
- Issue #17 branch (`feature/issue-17-estimate-issue-skill`) — still needs commit + push + PR + `ready 17`
- Uncommitted `.gitignore` (`.omc/`) + regenerated `docs/ARCHITECTURE.md` — land or discard separately
- Issue #14 (SonarQube) waiting on self-hosted infra, holds `decisions/0006-*`
- Verify a live decision comment for issue #15 (last acceptance box)
- Raise `jacoco.line.coverage` above 0.00 (follow-up from #3/#4)
