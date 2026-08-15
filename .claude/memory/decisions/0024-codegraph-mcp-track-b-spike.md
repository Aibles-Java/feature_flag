# 0024 — CodeGraphContext MCP spike, Track B (issue #50)

**Date:** 2026-08-15
**Status:** Done. Installed, registered, validated. ADR:
`docs/adr/ADR-0005-codegraph-mcp-track-b.md`. Implements Goal B of
[0014 — code graph adoption](0014-codegraph-adoption.md); Goal A is
[[0022-archunit-tier1-governance-gate]] / [[0023-archunit-tier2-immutable-flag-key]].
Branch `feature/issue-50-codegraph-mcp-spike`, **independent of #48/#49** (branched from `develop`).

## Decision: per-dev, NOT a committed `.mcp.json`

Registered at Claude Code **user scope** → `~/.claude.json`, outside the repo:

```
claude mcp add codegraphcontext --scope user \
  -e PYTHONIOENCODING=utf-8 -e CGC_RUNTIME_DB_TYPE=kuzudb \
  -- "C:/Users/ACER/.venvs/cgc/Scripts/cgc.exe" mcp start
```

`claude mcp list` → `codegraphcontext: ✔ Connected`.

Two concrete reasons a committed config would be *wrong*, not merely premature: the server command
is a machine-specific absolute venv path, and the working DB backend is OS-dependent.

## The spec's Track B setup steps do NOT work as written (on Windows)

1. **"Python 3.10–3.12, tree-sitter breaks on 3.13" is STALE.** Installed fine on **3.13.10**
   (tree-sitter 0.25.2 + language-pack 1.14.3); `cgc doctor` probes **8/8 parsers OK incl. Java**.
   No second Python runtime needed — don't install 3.12 on this warning alone.
2. **FalkorDB Lite is unavailable on Windows at ANY Python version.** `pip install falkordblite` →
   *"The redislite module is not supported on the 'win32' platform"*. It's a **platform** limit, not
   a Python-version one. The spec's version warning actively misdirects here: chasing 3.12 costs a
   system-wide install and still fails. **Fix: `DEFAULT_DATABASE=kuzudb`** in
   `~/.codegraphcontext/.env` (embedded, already a dependency, works on Windows). CLI value is
   **`kuzudb`**, not `kuzu` — `-db kuzu` errors out.
3. **`cgc mcp setup` has no Claude Code CLI target** (VS Code, Cursor, Windsurf, Zed, Claude
   *Desktop*, Gemini CLI, Cline, RooCode, Amazon Q, Goose, OpenCode) and is interactive — it would
   hang a non-interactive shell. Use `claude mcp add` directly.
4. **`UnicodeEncodeError` on a cp1252 console** — the CLI prints emoji. Set
   `PYTHONIOENCODING=utf-8` (baked into the MCP server env above) or every command dies in a rich
   traceback that looks like a tool bug.

## Validation, checked against grep ground truth (not eyeballed)

Index: 275 files / 150 Java → 572 functions, 129 classes, **5187 CALLS edges**, ~5.4 min.

`analyze callers requireRole` was **exact**: all 10 production call sites (`AuditService` 1,
`OrganizationServiceImpl` 4, `ProjectServiceImpl` 5), zero misses, zero false positives. It also
surfaced `PermissionService.requireRoleForProject → requireRole`, an internal delegation a naive
"grep callers, exclude the declaring file" misses. The apparent duplicate rows are **not a bug**:
the tool lists call *edges*, and that test method genuinely contains two `requireRole` calls.
(The issue's predicted "~4 service impls" was the imprecise part — written before `AuditService`.)

## The limitation, pinned down precisely

The spec says "tree-sitter approximate". Concretely: **queries are name-keyed, not symbol-keyed.**
`analyze callers getAllFlags` returns one bucket mixing `EvaluationController.getAllFlags`, the
`EvaluationService` interface method, and `EvaluationServiceImpl`'s override.

So accuracy is excellent for a **unique** method name and degrades exactly where names are shared —
overloads, and this repo's pervasive interface + `*Impl` pairing. **Never use it for
provable-absence** ("nothing calls X", "this is dead code"); that stays with Track A's
bytecode-exact ArchUnit. A `find_dead_code` hit is a lead, not a verdict.

Not enabled, but the obvious next knob: `ENABLE_INHERIT_RESOLVE=true` re-points interface calls to
concrete impls via the `INHERITS` graph — aimed at exactly this Spring/DI shape. Costs a full
re-index.

## Housekeeping

- No file watcher left running. `cgc watch .` / `ENABLE_AUTO_WATCH=true` is a per-dev choice; the
  index is a point-in-time snapshot, so re-run `cgc index .` after a big refactor.
- Removal is `claude mcp remove codegraphcontext -s user` + delete `C:\Users\ACER\.venvs\cgc` and
  `C:\Users\ACER\.codegraphcontext`. Nothing in `pom.xml`, nothing in the runtime.
- ADR is **0005**: 0004 is Track A's, which is still unmerged on the #48 branch, so
  `docs/adr/README.md` will likely conflict on merge — expected, resolve by keeping both rows.
