# 0014 — Code graph adoption: ArchUnit (governance) + CodeGraphContext MCP (agent queries)

**Date:** 2026-07-11
**Status:** Decided; spec written, 3 issues filed (#48/#49/#50). Not yet implemented.

## Decision

Adopt a code graph for `feature_flag`, split into **two goals with two different best
tools** (do not force one tool to serve both):

- **Goal A — Governance:** enforce architecture invariants as CI-gating tests →
  **ArchUnit** (`archunit-junit5`, bytecode-exact, zero new infra, runs in `mvn verify`
  via Surefire alongside Spotless/JaCoCo).
- **Goal B — Agent queries:** give Claude Code callers/callees/reachability/impact →
  **CodeGraphContext** MCP server (embedded FalkorDB/Kuzu, Python 3.12, tree-sitter).

Full spec: `docs/specs/codegraph-adoption.md` (includes a Mermaid solution-design diagram
with the 4-colour scheme: black=reusable, blue=modified, green=new, orange=external).

## Why / alternatives considered

- **jQAssistant + Neo4j** — the "one bytecode-precise graph for both goals" upgrade;
  closes the Tier-3 data-flow gaps ArchUnit can't prove (per-method guard coverage,
  layering-with-DI). Deferred: costs operating Neo4j + Cypher fluency. Revisit only when
  Tier-3 governance or agent-query precision is a felt need.
- **Joern / CPG** — held in reserve for a future security/taint pass on the auth code
  (JWT / API-key / SDK-auth), the one job neither chosen tool does.
- **Rejected:** Sourcegraph/Cody (enterprise-only since mid-2025), scip-java (query path
  needs Sourcegraph), jArchitect/Structure101 (paid/EOL), build-your-own (Spring-DI
  resolution + parser maintenance forever, no advantage at ~7k LOC; avoid Kuzu — repo
  archived ~Oct 2025).

## Key facts to carry forward

- **ArchUnit tests are static-only — they do NOT boot a Spring context** (no MockMvc/H2).
  So the Spring Boot 4.1 test landmines (`springboot4-*-test-quirks`, security-testing)
  **do not apply** to the ArchUnit test. Main unknown for #48 = pre-existing layering
  violations → wrap in `FreezingArchRule` to adopt on today's code.
- **Tier split** (spec §A.4): Tier-1 = 7 structural rules R1–R7 (layering, no ctrl→repo,
  no authz in controllers, `SecurityContextHolder` centralized, two-chain principal
  isolation, repos-are-interfaces, no cycles). Tier-2 = custom `ArchCondition`s (immutable
  `FeatureFlag.key`, `update()` ignores key). Tier-3 = data-flow rules ArchUnit CANNOT
  prove → documented, not overclaimed; belongs to jQAssistant later.
- **CodeGraphContext Java edges are tree-sitter approximate** (guess through Spring
  `@Autowired`/interface/DI) — navigation aid only, NOT for provable-absence claims;
  provable governance stays with ArchUnit (bytecode). Pin Python 3.12 (tree-sitter breaks
  on 3.13). If lossy → upgrade to FalkorDB code-graph (LSP-accurate).
- **Mermaid `linkStyle` is positional** (0-indexed by edge declaration order) — reordering
  or adding edges requires re-partitioning the index lists in the diagram. Flagged in the
  spec §9.5.

## Work items

- **#48** (M/5h) Track A Tier-1 + ADR-0003 + memory — the only deliverable touching CI. Blocks #49.
- **#49** (S/3h) Track A Tier-2 custom conditions. Depends on #48.
- **#50** (S/2h) Track B CodeGraphContext spike. Independent; run after ~1 week of Tier-1 use.

Sequencing: #48 → #50 (spike) → #49 → re-evaluate jQAssistant. See [[0012-harness-guards-spotless-coverage]]
(same hooks/gates philosophy this extends) and [[0007-estimate-issue-skill]].
