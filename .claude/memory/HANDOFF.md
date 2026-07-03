# Handoff

*Ephemeral — overwritten by `/save-memory` at the end of each session. Read this first.*

## Current WIP

**Issue #23** (externalize secrets, fail fast on placeholder config) on branch
`feature/issue-23-externalize-secrets` (→ `develop`). Implementation complete and verified:

- `config/JwtProperties.java` (new) — validated `@ConfigurationProperties` record:
  @NotBlank/@Positive + @AssertTrue checks (≥64 UTF-8 bytes, placeholder markers,
  unresolved `${...}` literal, ≥10 distinct chars), masked `toString()`.
- `security/JwtTokenProvider.java` — injects `JwtProperties` (drops `@Value`);
  `FeatureFlagApplication` gains `@ConfigurationPropertiesScan`.
- `application.properties` — dev-only secret replaces `change-me-...`;
  `application-prod.properties` (new) — no-default `${VAR}` placeholders.
- `Dockerfile` — `ENV SPRING_PROFILES_ACTIVE=prod` (security-review HIGH fix) + EXPOSE 8081.
- `docker-compose.yml` parameterized, `.env` gitignored, `.env.example` (new), `README.md`
  (new), CLAUDE.md Key configuration updated.
- Tests: `config/JwtPropertiesValidationTest` (12 cases via ApplicationContextRunner),
  `JwtTokenProviderTest` updated. **52/52 green** (`./mvnw verify`).
- Manual prod-profile fail-fast verified: missing var / placeholder / short secret all
  abort with clear messages; prod + valid secret and default dev profile both start.

Reviews done: java-reviewer (approve; .env.example added), security-reviewer
(HIGH profile-fallback → Dockerfile fix; MEDIUM entropy → distinct-chars check; LOW
toString → masked). Board card is *In progress*; two decision comments posted on #23
(compose scope, README location).

**Remaining:** commit, push, PR (`Closes #23`, base `develop`), then
`.claude/scripts/issue-board.sh ready 23`.

## Context to Load

- `decisions/0008-secrets-externalization-fail-fast.md` — the design + rationale.
- `conventions/springboot-configprops-binding-gotchas.md` — binder/validation gotchas.

## Next steps

1. Commit this work (do NOT include pre-existing dirty files:
   `.claude/skills/estimate-issue/calibration.md`, `docs/ARCHITECTURE.md`, `.omc/`,
   `.claude/memory/.omc/` — they belong to parked work).
2. Push + `create-pr` skill (`Closes #23`) → `issue-board.sh ready 23`.

**Parked from previous sessions:**
- Issue #17 branch (`feature/issue-17-estimate-issue-skill`) — needs commit + push + PR + `ready 17`
- Uncommitted `.gitignore`(`.omc/`)/`docs/ARCHITECTURE.md` regeneration — land or discard separately
- Issue #14 (SonarQube) waiting on infra, holds `decisions/0006-*`
- Verify a live decision comment for issue #15 (last acceptance box) — two decision
  comments were posted on #23 this session, which may satisfy the check
- Raise `jacoco.line.coverage` above 0.00 (follow-up from #3/#4)
- GitHub GraphQL rate limit was hit once this session (board lookups); if
  `issue-board.sh` dies with "could not locate or create board item", check
  `gh api rate_limit` before debugging the script
