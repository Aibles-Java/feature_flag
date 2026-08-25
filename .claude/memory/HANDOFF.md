# Handoff

*Ephemeral — overwritten by `/save-memory` at the end of each session. Read this first.*

## Current WIP

Branch **`feature/role`** — the ABAC / project-grant authorization work, brought up to date with
`develop` and being pushed + PR'd now. Four commits on top of the original `ffd356d`:

- `d3f57e9` renumber migrations `009–013` → `013–017`
- `950de15` drop the case-colliding `docs/ARCHITECTURE.md` (mandatory — the merge could not start
  otherwise; see [[windows-docs-case-collision]])
- `8a15239` **merge `origin/develop`** — 13 Java conflicts resolved, `./mvnw test` green
- `f576d9c` docs mapped onto the merged state (ADR-0006 written, ADR-0001 marked superseded,
  CLAUDE.md + architecture.md permission sections rewritten)
- `903ba7a` `AUDIT_READ` + audit rows for permission changes
- `5b7b476` `organisations` spelling + pagination on the two management list endpoints, plus two
  sequence diagrams in `docs/ABAC.md` §4

`./mvnw test`: **316 tests, 0 failures.** Tree otherwise clean apart from three untracked files
that belong to the user, not the branch: `.cgcignore`, `docs/demo/`, `docs/main-flows.md` — do not
commit them (they rode along twice this session and had to be backed out with `git rm --cached`).

## Context to Load

- [[0023-abac-branch-merge-and-audit-mapping]] — why the adapters came back, how the conflicts were
  resolved, and the two model gaps that were closed.
- [[sequential-ids-collide-across-long-lived-branches]] — before adding any migration or ADR.
- `docs/ABAC.md` §12 — the authoritative open-gaps list, kept in the repo rather than here.

## Next steps

Nothing is blocking the PR; everything below is follow-up, roughly in value order.

1. **Tests are the real gap before merge.** The design spec's §9 plan is only half delivered: no
   `ProjectMemberControllerTest` / `CustomRoleControllerTest` (every other admin controller has
   one), no repository test for `PermissionGrantRepository` / `CustomRoleRepository`, no
   `@SpringBootTest` for Scenario A (project-scoped access) or B (production protection).
   `listGrants` / `list` have **no test at all** — they were changed to paginate with nothing
   guarding them.
2. **`ORG_READ` / `MEMBER_READ`** — `OrganizationServiceImpl.get` and `listMembers` still gate on
   `isMember`, so custom roles cannot express them. Same fix shape as `AUDIT_READ` in `903ba7a`.
3. **`updateMemberRole` does not exist** anywhere — a member's org role can only be set at invite
   time, and remove-then-reinvite now also wipes their project grants.
4. **Orphaned grants** — `ProjectServiceImpl.delete` and `OrganizationServiceImpl.delete` leave
   `permission_grant` rows behind (`scope_id` is polymorphic, no FK). `removeMember` already does
   this cleanup; copy it.
5. Small: `EnvironmentSecretResponse` missing `type` / change window; `updateState` resolves the
   environment before checking permission (404-vs-403 probe); `mostPermissive` ranks roles by
   action-set size (works, but fragile — use an explicit rank);
   `PermissionGrantRepository.existsByUser_IdAndScopeTypeAndScopeId` is dead code; Postman
   collection has no grant / custom-role requests.

Note for whoever picks this up: **there is no GitHub issue for ABAC** — the PR is the first tracked
artifact. If it should be tracked on the board, open one and link it.
