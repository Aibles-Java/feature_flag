# Handoff

*Ephemeral — overwritten by `/save-memory` at the end of each session. Read this first.*

## Current WIP — three code-graph branches in flight

| Issue | Branch | PR | State |
|---|---|---|---|
| #48 Track A Tier-1 (ArchUnit R1–R7) | `feature/issue-48-archunit-governance` | **#65** → `develop` | CI green, open |
| #49 Track A Tier-2 (R8/R9 immutable key) | `feature/issue-49-archunit-tier2` | **#66** → #48's branch | **stacked**, local verify green, CI doesn't run on stacked PRs |
| #50 Track B (CodeGraphContext MCP) | `feature/issue-50-codegraph-mcp-spike` | not opened yet | branched from `develop`, independent |

**Merge order:** #65 → then retarget #66 to `develop` → #50 any time (independent).
`docs/adr/README.md` will conflict between #50 and #65/#66 (each adds ADR rows) — keep both.

## Context to Load

- `decisions/0024-codegraph-mcp-track-b-spike.md` — why the spec's Track B setup steps fail on
  Windows, and the precise shape of the tool's inaccuracy.
- `decisions/0022-archunit-tier1-governance-gate.md` + `0023-archunit-tier2-immutable-flag-key.md`
  — the vacuous-rule traps in both ArchUnit tiers.

## Recurring lesson across all three issues

Every one of the three had **spec/issue instructions that were wrong or vacuous against the real
code**, and each was only caught by verifying against the actual artifact (bytecode via `javap`,
grep ground truth, a real install) rather than trusting the text:

- #48: the spec's R4 doesn't compile; R1 needed extra layers.
- #49: *both* stated conditions can never fire under Lombok.
- #50: the Python-3.13 warning is stale, and the real blocker (Windows) isn't mentioned at all.

## Environment notes

- **codegraphcontext MCP is now registered at user scope** (`~/.claude.json`) and reports
  `✔ Connected`. Its tools become callable in a **new** Claude Code session. Backend is `kuzudb`;
  index is a point-in-time snapshot — re-run `cgc index .` after big refactors. No watcher running.
- `gh` is off-PATH at `C:\Users\ACER\AppData\Local\gh-cli\bin`.
- FE (`feature_flag_ui`): `npm install` then `npm run dev` → :5173. Backend is on **8081**.

## Known bug found while running the FE — still NOT filed

`POST /api/v1/auth/login` with wrong credentials returns **500, not 401**.
`GlobalExceptionHandler` has no handler for Spring Security's `AuthenticationException`, so
`BadCredentialsException` falls through to `@ExceptionHandler(Exception.class)`. Pre-existing on
`develop`. Users see a system error instead of "wrong password", and the FE's axios interceptor
(only handles 401) never clears the token. Touches auth → needs a security review + its own issue.

## Next steps

1. Open the PR for #50 and run `.claude/scripts/issue-board.sh ready 50`.
2. Land #65, retarget #66.
3. File the login-500 bug.
4. Optional follow-ups: `ENABLE_INHERIT_RESOLVE=true` + re-index if name-keyed query results prove
   annoying; unfreeze ArchUnit R7 by breaking the `config` ↔ `security` cycle; a meta-test that
   asserts each arch rule can actually fire.
