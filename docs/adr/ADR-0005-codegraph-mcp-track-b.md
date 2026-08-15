# ADR-0005: CodeGraphContext MCP as a per-developer navigation aid (Track B)

**Status:** Accepted (as a time-boxed experiment)
**Date:** 2026-08-15
**Relates to:** spec `docs/specs/codegraph-adoption.md` §Track B, issue #50. Track A (governance) is
ADR-0004 and is independent of this.

## Context

Track A made architecture invariants build-gating. Track B is the other half of the code-graph
decision: give the coding agent *navigation* — callers/callees/reachability/impact/dead-code — so it
answers structural questions with a graph query instead of a grep→read→re-grep loop that produces
false positives on common names (`update`, `value`, `save`, `get`).

The spec proposed `codegraphcontext` (MIT, alpha) as a dev-machine MCP server, explicitly *not*
coupled to the repo runtime, and asked for a decision on whether its MCP config should be
per-developer or committed to `.mcp.json`.

## Decision

**Adopt it as a per-developer tool. Do not commit `.mcp.json`.**

Registered at Claude Code **user scope** (`claude mcp add codegraphcontext --scope user`), which
writes to `~/.claude.json` — outside the repository. `claude mcp list` reports
`codegraphcontext: ✔ Connected`.

The per-dev choice is not merely the spec's default-by-caution; the spike produced two concrete
reasons a committed config would be actively wrong here:

1. **The server command is a machine-specific absolute path.** It runs from an isolated virtualenv
   (`C:/Users/ACER/.venvs/cgc/Scripts/cgc.exe`). A committed `.mcp.json` would hardcode one
   developer's path and break for everyone else.
2. **The working database backend is OS-dependent** (see below). A committed config would have to
   pin a backend that is correct on one platform and broken on another.

Revisit only if the team later standardises install location and platform.

## What the spike actually found (the spec's setup steps do not survive Windows)

The spec's §B.3–B.4 are written for a Unix-ish machine. Three of its instructions are wrong here,
and the *reasons* matter more than the workarounds:

1. **"Python 3.10–3.12 — tree-sitter bindings break on 3.13."** **Stale.** Installed fine on
   **Python 3.13.10**: `tree-sitter 0.25.2` + `tree-sitter-language-pack 1.14.3`, and
   `cgc doctor` probes **8/8 parsers OK including Java**. No second Python runtime was installed.
2. **"Embedded FalkorDB Lite default — no DB server to operate."** **Not available on Windows at
   all** — `pip install falkordblite` fails with *"The redislite module is not supported on the
   'win32' platform"*. This is a **platform** limitation, not a Python-version one, so installing
   3.12 would not have fixed it. The spec's Python-version warning would have sent a developer
   chasing the wrong thing.
   → Resolved by switching `DEFAULT_DATABASE` to **`kuzudb`**, the embedded fallback, which is
   already a dependency and works on Windows. (Note the CLI flag value is `kuzudb`, not `kuzu`.)
   Caveat, carried over from the spec's own §7: the Kuzu upstream repo was archived ~Oct 2025.
3. **`cgc mcp setup` does not target Claude Code.** Its picker covers VS Code, Cursor, Windsurf,
   Zed, Claude *Desktop*, Gemini CLI, Cline, RooCode, Amazon Q, Goose, OpenCode — Claude Code CLI is
   absent, and the command is interactive. Registration was done directly with `claude mcp add`
   instead.

Also worth knowing: the CLI writes emoji, so on a `cp1252` console it dies with
`UnicodeEncodeError`. `PYTHONIOENCODING=utf-8` fixes it and is baked into the MCP server env.

## Validation

Indexed the repo (`cgc -db kuzudb index .`): 275 files scanned, 150 Java, **572 function nodes, 129
class nodes, 5187 CALLS edges**, ~5.4 min. One call was honestly reported as unresolved
(`metrics.recordEvaluation`, overloaded).

The spec's acceptance query, checked against `grep` ground truth rather than eyeballed:

| | Result |
|---|---|
| `analyze callers requireRole` | **Exact.** All 10 production call sites — `AuditService` (1), `OrganizationServiceImpl` (4), `ProjectServiceImpl` (5) — no misses, no false positives. |
| Bonus edge grep misses | `PermissionService.requireRoleForProject` → `requireRole`, an internal delegation invisible to a naive "find callers" grep that excludes the declaring file. |
| Apparent duplicate rows | **Not a defect.** `requireRoleEnforcesEachRoleExactly` contains two distinct `requireRole` calls; the tool lists call *edges*, not distinct methods. |

The issue predicted "~4 service impls"; the true answer is 3 classes / 10 sites. The prediction was
written before `AuditService` existed and counted loosely — the tool was right and the issue text
was the imprecise part.

## The limitation, stated precisely

The spec says Java edges are "tree-sitter heuristic / approximate". The spike pins down *how*:

**Queries are name-keyed, not symbol-keyed.** `analyze callers getAllFlags` returns a single bucket
mixing `EvaluationController.getAllFlags` (the endpoint), `EvaluationService.getAllFlags` (the
interface), and `EvaluationServiceImpl.getAllFlags` (the impl). Accuracy is therefore excellent when
a method name is unique in the codebase (`requireRole`) and degrades exactly where the name is
shared — overloads, and this repo's pervasive interface + `*Impl` pairing.

**Consequence:** it is a navigation aid. It must never be used for provable-absence claims
("nothing calls X", "this is dead code") — that job stays with Track A's bytecode-exact ArchUnit
rules. A `find_dead_code` hit is a lead to verify, not a verdict.

Tuning left off deliberately: `ENABLE_INHERIT_RESOLVE=true` re-points interface calls to concrete
implementations using the `INHERITS` graph and is aimed squarely at Spring/DI codebases like this
one. It costs a full re-index, so it is the obvious first thing to try if the name-keying above
proves annoying in practice.

## Consequences

- **Good:** Real graph queries over this repo at zero repo cost — nothing in `pom.xml`, nothing in
  the runtime, no container. Removing it is `claude mcp remove` plus deleting one virtualenv.
- **Good:** Java/Spring-aware tools beyond generic navigation (`find_java_spring_beans`,
  `find_java_spring_endpoints`, `execute_cypher_query`, `find_dead_code`, complexity/impact).
- **Bad:** Alpha software, and the index is a point-in-time snapshot. Answers are "as of last
  index"; re-run `cgc index .` (or `cgc watch .`) after significant refactors.
- **Bad:** ~5 minutes to re-index this small repo. Not something to run casually mid-task.
- **Neutral:** No file watcher was left running. `cgc watch .` (or `ENABLE_AUTO_WATCH=true`) is a
  per-developer choice; leaving a daemon on someone's machine is not a decision this ADR should make
  for them.

## Alternatives Considered

- **Commit `.mcp.json` so the whole team gets it automatically.** Rejected for the two concrete
  reasons above (absolute path, OS-dependent backend). Reconsider once the install is standardised.
- **Install Python 3.12 to get FalkorDB Lite.** Rejected once the failure was correctly attributed:
  `redislite` is unsupported on win32 regardless of Python version, so this would have cost a
  system-wide Python install and still not worked.
- **Run FalkorDB in Docker** (the repo already uses `docker compose` for Postgres). A reasonable
  path if KuzuDB's archived status becomes a problem, at the cost of a container to operate —
  precisely the "no DB server" property the spec chose this tool for.
- **Skip Track B, rely on Track A + grep.** Rejected: they answer different questions. ArchUnit
  proves invariants; it cannot tell you who calls what.
