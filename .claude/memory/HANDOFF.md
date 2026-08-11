# Handoff

*Ephemeral — overwritten by `/save-memory` at the end of each session. Read this first.*

## Current WIP

**Issue #38** (environment cloning + flag import/export) on branch
`feature/issue-38-env-clone-import-export` (→ `develop`), branched fresh from
`origin/develop`. Implementation **complete and committed** (`b637b71`); PR not yet
opened at the time of writing.

- `./mvnw verify` green: **276 tests, 0 failures, Spotless clean, JaCoCo floor met.**
- Three endpoints under `/api/v1/environments/{envId}`: `POST /clone`, `GET /export`,
  `POST /import`. All require OWNER/ADMIN on the org.
- **Zero Liquibase changesets** — the two new `AuditAction` values (CLONE, IMPORT) fit
  the existing bare `VARCHAR(32)` `audit_log.action` column.
- 26 new tests (`EnvironmentTransferServiceImplTest` 19, `EnvironmentTransferControllerTest` 7),
  one per acceptance criterion including the lossless round-trip (real Jackson
  serialize → deserialize) and the dry-run no-write assertion.

**Files added:** `service/EnvironmentTransferService` + `service/impl/…Impl`,
`controller/admin/EnvironmentTransferController`, `dto/request/{CloneEnvironmentRequest,
ImportEnvironmentRequest}`, `dto/response/{EnvironmentSnapshotResponse,
ImportResultResponse}`, `domain/enums/{ImportConflictStrategy, ImportOutcome}`,
`exception/InvalidRequestException`.
**Modified:** `GlobalExceptionHandler` (+400 handler), `AuditAction` (+CLONE, +IMPORT),
`FlagEnvironmentStateRepository` (+`findAllByEnvironmentIdOrderByFlagKey`), `CLAUDE.md`,
`docs/architecture-design-v1.md`.

**Review gates:** the session ran without the code-reviewer / security-review subagents
(the operator's session config disallowed spawning them). The security-sensitive surface
was self-reviewed instead — fresh key on clone, no secret in any snapshot or audit row,
project derived from the path env not the payload, `@Size(max = 2000)` on the flag list.
Worth a real `/review-pr` pass on the PR.

## Context to Load

- `decisions/0021-environment-clone-import-export.md` — the design and its rationale.
- `decisions/0020-audit-log-flag-org-mutations.md` — audit conventions this follows.

## Next steps

1. Open the PR with the `create-pr` skill (base `develop`, `Closes #38`), then
   `.claude/scripts/issue-board.sh ready 38`.
2. Run `/review-pr` on it — the self-review above is not a substitute.
3. **File a follow-up issue:** `EnvironmentServiceImpl.create()` doesn't backfill
   `FlagEnvironmentState` rows for the project's existing flags, so a newly created
   environment has no state rows and the SDK returns nothing for it. The fan-out only
   exists in the other direction (`FeatureFlagServiceImpl.create`). Import works around
   it; the asymmetry itself is untouched.
