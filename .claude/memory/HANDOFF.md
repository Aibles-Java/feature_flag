# Handoff

*Ephemeral — overwritten by `/save-memory` at the end of each session. Read this first.*

**Last updated:** 2026-09-03

## Current WIP

Branch **`feature/role`** (PR #87), worked in a git worktree at
`C:\Users\ACER\Desktop\aibless\feature_flag-role` so the main checkout could stay on
`feature/issue-38-env-clone-import-export`. Three commits, tree clean, `./mvnw test`
**470 tests / 0 failures**, `spotless:check` clean.

- `42ee946` **merge `origin/develop`** — the branch was 39 behind. Five conflicts, all unions
  (`AuditAction`, `db.changelog-master.xml`, `docs/adr/README.md`, `MEMORY.md`, `HANDOFF.md`);
  the four service impls auto-merged. Migrations renumbered `013–017` → **`014–018`** (filenames
  *and* `changeSet id`s) because develop took `013` for flag hygiene; memory decision
  `0023-abac-branch-merge` → **`0034`** because develop took `0023` for the Trivy pin.
- `5080e4c` **production protection generalised** — `PRODUCTION_ELEVATED` table, three new
  OWNER-only actions, rule D on all of them, `check()` resolves which prod envs an action touches.
- `1c546ec` **import routed through the PDP** — the fourth bypass, found by the code-reviewer
  agent after `5080e4c` was already committed.

Docs updated in the same commits: ADR-0006 amendment (2026-09-03), `docs/ABAC.md`
§4/§5/§10/§11/§12, `CLAUDE.md` permission section.

## Context to Load

- [[0035-production-protection-covers-every-production-reaching-action]] — what was decided,
  what was rejected, and the strictest-wins sharp edge.
- [[abac-role-adapters-bypass-attribute-rules]] — read before touching any `check(...)` call
  site or trusting that an attribute rule is enforced.
- [[0034-abac-branch-merge-and-audit-mapping]] — the earlier merge of this branch.
- `docs/ABAC.md` §12 — the authoritative open-gaps list, kept in the repo rather than here.

## Next steps

1. **PR #87's body still describes the pre-merge state.** It needs a note about the two security
   commits and the new 403s: an ADMIN archiving a flag in a project that has a `PRODUCTION`
   environment, rotating a production key, or importing a snapshot into production.
2. **Decide the strictest-wins sharp edge.** Disjoint change windows across two production
   environments block archiving around the clock. Recoverable by an OWNER (`ENV_UPDATE` is not
   window-guarded) but not by a custom role holding `FLAG_ARCHIVE_PRODUCTION` alone. The
   alternative — apply rule D only when the call site names a single environment — is ~5 lines.
3. **Convert the remaining `requireRole*` call sites**: `EnvironmentTransferServiceImpl.clone`
   and `.export`, `FlagHygieneServiceImpl.list`, eight in `WebhookSubscriptionServiceImpl`. The
   webhook ones deserve the first look — subscribing to a production environment streams every
   production flag change to an arbitrary URL on ADMIN authority alone.
4. **Tests are still the gap before merge** (unchanged from the previous handoff): no
   `ProjectMemberControllerTest` / `CustomRoleControllerTest`, no repository test for
   `PermissionGrantRepository` / `CustomRoleRepository`, no `@SpringBootTest` for Scenario A
   (project-scoped access) or B (production protection). `listGrants` / `list` have no test.
5. Still open from before: `ORG_READ` / `MEMBER_READ` (`OrganizationServiceImpl.get` and
   `listMembers` gate on `isMember`), no `updateMemberRole`, grants orphaned when a project or
   org is deleted.

Housekeeping: three untracked files in the *main* checkout belong to the user, not any branch —
`.cgcignore`, `docs/demo/`, `docs/main-flows.md`; do not commit them. The worktree can be removed
with `git worktree remove ../feature_flag-role` once the branch is merged.
