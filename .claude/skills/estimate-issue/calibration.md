# Estimate calibration log

Append-only. One row per estimated issue; fill `Actual (h)` and `Δ` when the issue
reaches Done (see `SKILL.md` → Calibration). `Δ` = Actual − Estimate.

| Issue | Estimated on | Size | Estimate (h) | Actual (h) | Δ | Basis / notes |
|-------|--------------|------|--------------|------------|---|---------------|
| #17 | 2026-07-02 | M | 4 | | | Skill authoring + script subcommand; estimated manually before this skill existed (its own first test case) |
| #48 | 2026-07-11 | M | 5 | | | Code graph Track A Tier-1: pom dep + 7 ArchUnit rules (first-time API) + ADR-0003 + memory + negative-test proof. ArchUnit is static-only (no Spring context) → Boot-4.1 test landmines don't apply; main unknown = pre-existing layering violations (FreezingArchRule) |
| #49 | 2026-07-11 | S | 3 | | | Code graph Track A Tier-2: 2 custom ArchConditions (field-set `key` + method-call `getKey`); single-file, setup reused from #48; custom-condition API is the risk (rounded up within S) |
| #50 | 2026-07-11 | S | 2 | | | Code graph Track B: CodeGraphContext MCP spike; ~1h install+validate + Python 3.12 env + alpha tool (round XS→S) |
