# 0018 — Append-only audit log for admin mutations (issue #31)

**What:** A compliance audit trail — one row per admin mutation, with before/after JSON — plus a
paginated per-org read endpoint.

## Decisions
- **Synchronous, same-transaction write (NOT the Slack event pattern).** `AuditService.record(...)`
  is a plain method called directly inside each mutating service method's own `@Transactional`, so
  the audit row commits atomically with the mutation (rollback ⇒ no orphan row; commit ⇒ exactly one
  row). Deliberately NOT `@Async` / `@TransactionalEventListener(AFTER_COMMIT)` like `SlackEventListener`
  — those would break the "exactly one row per mutation" guarantee. Trade-off accepted: an audit-write
  failure rolls back the mutation (correct for a compliance ledger).
- **`AuditService` is a single `@Service`** (no interface), following the `PermissionService`
  precedent. It both writes (`record`) and reads (`list`).
- **before/after = the entity's response DTO serialized to `Map<String,Object>`** via Jackson. Reuses
  the existing `toResponse`/`toMemberResponse` helpers, so no secret fields are ever in scope. Create
  ⇒ before null; delete ⇒ after null.
- **NEVER audit secrets.** Environment `create` audits `toResponse` (the non-secret `EnvironmentResponse`),
  NOT `EnvironmentSecretResponse`. `rotateApiKey` records the event with before/after **null** (no key,
  no hash). Explicitly tested (`EnvironmentServiceImplTest.rotate…` verifies `record(API_KEY, …, null, null)`).
- **JSON storage:** `@JdbcTypeCode(SqlTypes.JSON)` + `Map<String,Object>` field; Liquibase
  `${json.type}` property = `jsonb` (postgresql) / `json` (h2). **Validated round-tripping on real H2**
  (`AuditLogRepositoryTest`) — this was the flagged risk in the issue and it works with no fallback needed.
- **Self-contained `ObjectMapper` inside AuditService** (`new ObjectMapper().findAndRegisterModules()
  .disable(WRITE_DATES_AS_TIMESTAMPS)`). Reason: **Boot 4.1 does NOT autoconfigure an `ObjectMapper`
  bean in a non-web (`webEnvironment=NONE`) context** → injecting one broke every `@SpringBootTest`
  repo-test context. Building our own also gives ISO-string dates (JSR-310) independent of the web stack.
- **Read endpoint** `GET /api/v1/organisations/{orgId}/audit-log` (`AuditController`, British
  "organisations" to match the rest of the API — issue text said "organizations"). Paginated (reuses
  #33 `PageResponse`), **newest-first** (`@PageableDefault(sort=createdAt,id, direction=DESC)`),
  VIEWER+ enforced via `AuditService.list` → `permissionService.requireRole`.
- **Append-only + no FKs.** No update/delete code paths. `audit_log` has NO foreign keys on
  `actor_user_id`/`org_id` — it's an independent historical ledger that must outlive the entities it
  records (a user/org delete must not cascade away its audit history).
- **16 audit points**: org create/update/delete/inviteMember/removeMember; project create/update/delete;
  environment create/update/delete/rotateApiKey; flag create/update/archive/unarchive/updateState.
  Flags have no hard-delete (archive/unarchive only), so none is audited for flag DELETE.

## Dependency / stacking
- Branch `feature/issue-31-audit-log` is **stacked on `feature/issue-33-pagination-admin-list`** (PR #57,
  still open) because the read endpoint needs #33's `PageResponse`/`PaginationConfig`. When #33 merges,
  rebase #31 onto develop. Enum entityId for FLAG_STATE = the `FlagEnvironmentState` id.

## Enum / schema notes
- `AuditAction`: CREATE, UPDATE, DELETE, ARCHIVE, UNARCHIVE, INVITE_MEMBER, REMOVE_MEMBER,
  ROTATE_API_KEY, CHANGE_STATE. `AuditEntityType`: ORGANIZATION, PROJECT, ENVIRONMENT, FEATURE_FLAG,
  FLAG_STATE, MEMBER, API_KEY. Both stored `@Enumerated(STRING)`.
- Test gotcha: service unit tests that build a `Project` now need `.organization(org)` set, because
  audit resolves `orgId` via `project.getOrganization().getId()` (would NPE otherwise).

## Verification
- `./mvnw verify`: 221 tests green, Spotless clean, JaCoCo 0.83 met. code-reviewer agent: no
  CRITICAL/HIGH/MEDIUM (confirmed no secret leakage, exactly-one-row, atomicity, auth, migration).
