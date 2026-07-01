# 0001 — Adopt Tier 3 Claude Code harness

**Date:** 2026-07-01

## Decision

Ran `/shipwithai-starter:init` at Tier 3 (Full) for this repo. Key choices made during
the interview:

- **Branch strategy:** Gitflow (`feature/*` → `develop`, `release/*`/`hotfix/*` → `main`).
  Never commit directly to `main`.
- **Commit format:** Conventional Commits.
- **Coverage target:** 80%, not CI-enforced.
- **Workflow gates:** plan-before-code (tasks >30min), code-review after every significant
  change, security-review before touching sensitive areas.
- **Sensitive areas:** `security/` + JWT config, `db/changelog/migrations/` (Liquibase,
  append-only), `ApiKeyGenerator`/API-key rotation.
- **No formatter/linter** — none detected (no checkstyle/spotless/editorconfig); code style
  follows convention only.
- **MCP servers:** none configured (declined GitHub/Postgres MCP for now).

## Why

Team size is small (2 devs), project is Java 21/Spring Boot 4.1.0/Maven, actively developed.
Full tier chosen to get SSOT docs (ADR/CODEMAPS) and a drift-monitor agent up front rather
than retrofitting later.

## Notable gotcha

The installed `shipwithai-starter@2.4.0` plugin is missing its `assets/memory/*.tmpl` files —
the Tier-3 self-sustaining `.claude/memory/` lifecycle (store + SessionStart/Stop hooks +
`/save-memory` skill) could not be copied from templates as the skill instructions describe.
It was hand-authored directly from the `setup-memory` `SKILL.md` spec instead — functionally
equivalent but not a byte-for-byte reproduction of ShipWithAI's official templates. See
[[0002-hand-authored-memory-lifecycle]] if that file exists, otherwise see `conventions/`.

## Also recommended but not installed

`shipwithai-java-backend-toolkit` plugin (Spring Boot/JPA guardrails — missing `@Version`,
non-public `@Transactional`, N+1 detection) — the `shipwithai` marketplace is added, but the
plugin itself isn't enabled yet. Install via `/plugin`, then re-run
`/shipwithai-starter:init --update` to wire it in.
