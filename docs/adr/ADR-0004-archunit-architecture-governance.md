# ADR-0004: ArchUnit as the Architecture Governance Gate

**Status:** Accepted
**Date:** 2026-08-12
**Supersedes / relates to:** spec `docs/specs/codegraph-adoption.md` (Track A), issue #48

> **Numbering note:** the spec and issue #48 both call this "ADR-0003". That number was already
> taken by [ADR-0003 (Pagination Strategy)](ADR-0003-pagination-strategy.md), so this record is
> ADR-0004. The spec text was not rewritten; this note is the reconciliation.

## Context

Several of this codebase's correctness rules are *relationship-shaped* — "controllers must not
touch repositories", "authorization lives in `PermissionService`, not in controllers", "the JWT
admin principal must never leak into the SDK chain". They are written as prose in `CLAUDE.md` and
`docs/architecture.md`, which means they are enforced only by human review. Prose invariants decay:
a reviewer who does not know the rule, or an agent generating plausible-looking code, breaks them
silently and nothing in the build objects.

`docs/specs/codegraph-adoption.md` evaluated adopting a *code graph* to make these rules
machine-checkable, and split the problem into two goals with different best tools: **governance**
(build-gating enforcement) and **agent navigation** (callers/callees/impact queries). This ADR
records the governance half — Track A. Track B (CodeGraphContext MCP) is a separate, optional,
dev-machine-only experiment and is not decided here.

## Decision

**Adopt ArchUnit (`com.tngtech.archunit:archunit-junit5:1.4.2`, test scope) and encode the Tier-1
invariants as JUnit tests in `src/test/java/org/aibles/feature_flag/architecture/ArchitectureTest.java`.**

Seven rules ship:

| Rule | Invariant |
|------|-----------|
| R1 | Layering: Controller → Service → Repository; controllers accessed by nobody |
| R2 | Controllers must not depend on `repository/` |
| R3 | No authz logic in controllers (no `PermissionService` / `MemberRole` dependency) |
| R4 | `SecurityContextHolder` access centralized (`PermissionService` + the `security` filters only) |
| R5 | `UserPrincipal` (admin chain) stays out of `controller.sdk` |
| R6 | Every type in the repository layer is an interface |
| R7 | No cycles between top-level package slices |

**No build-plugin change.** ArchUnit rules are ordinary JUnit 5 tests, so Surefire already runs them
in `./mvnw test` and `./mvnw verify`, alongside the existing Spotless check and JaCoCo ratchet. CI
(`.github/workflows/ci.yml` runs `./mvnw --batch-mode --no-transfer-progress verify`) picks them up
with zero pipeline edits.

### Deviations from the spec's rule sketch, and why

The spec's §A.4 code sketch was written against the repository as of 2026-07-10. Three adjustments
were needed against the code as it actually stands:

1. **R1 declares `Security` and `Config` as layers** that may also reach `Repository`. This is not a
   loosening for convenience — it is a fact about how authentication must work:
   `ApiKeyAuthenticationFilter` and `CustomUserDetailsService` resolve the authenticated principal
   from the database *before* any service exists to call, and `SecurityConfig` injects that
   repository into the filter. Every other package remains barred from the repository layer, and the
   rule that actually matters (R2, controllers) is asserted separately and unconditionally.

2. **R4 is inverted into a `noClasses(...).should().accessClassesThat(...)` form**, and the
   `security` package is allowed alongside `PermissionService`. The spec's
   `classes().that().accessClassesThat()` form does not compile against ArchUnit 1.4.2 —
   `accessClassesThat()` is not on the `ClassesThat` interface. The `security` allowance is
   necessary because the filters' *job* is to populate the holder; ArchUnit sees a static method
   call and cannot distinguish a write from a read. The invariant that survives — and the one worth
   having — is that no service, no other controller, and no support package performs an ad-hoc,
   cast-unsafe principal read.

   **The spec's third exemption, `EvaluationController`, is deliberately dropped.** Review found it
   was never exercised: the SDK controller takes its principal as an injected `Authentication`
   method parameter and does not touch `SecurityContextHolder` at all. An unexercised allowance is
   not free — it pre-authorises a regression nobody would notice. Removing it makes R4 strictly
   tighter (verified: adding such a read to `EvaluationController` now fails the build) and pins the
   better pattern. `CLAUDE.md`'s SDK-chain description, which asserted the controller reads the
   holder, was corrected in the same change — the prose had drifted from the code.

3. **R7 is wrapped in `FreezingArchRule`** against a committed baseline
   (`src/test/resources/archunit_store`, configured in `src/test/resources/archunit.properties`).
   One cycle pre-dates the rule: `config` → `security` (`SecurityConfig` constructs the filter
   beans) and `security` → `config` (`JwtTokenProvider` reads `JwtProperties`). Breaking it means
   relocating the JWT configuration properties — a change to a sensitive area, and not something to
   smuggle into a governance PR. Freezing gates *new* cycles from today instead of deferring the
   whole rule. `freeze.refreeze=false` is set explicitly so a new violation fails the build rather
   than silently widening the baseline.

   **`allowStoreCreation` is set to `false`, which matters more than it looks.** With store creation
   enabled — the natural setting, and the one needed to generate the baseline in the first place — a
   *missing* store makes ArchUnit build a fresh baseline from whatever the code looks like right now
   and report green. The gate would pass precisely when it had lost the thing it gates against, and
   a bad `.gitignore`, a sparse checkout, an accidental `git rm`, or an IDE run-config rooted
   outside the module would all trigger it. This was confirmed empirically before being fixed:
   with the store moved aside and creation allowed, `mvn test` exited 0 and silently wrote a new
   baseline. With creation disabled the run now fails loudly with
   `StoreInitializationFailedException`. Re-freezing after a legitimate refactor is a deliberate
   four-step procedure documented in `archunit.properties`.

### What is deliberately *not* claimed

ArchUnit reasons over bytecode dependency and access edges. It is exact for structural rules and is
**not** a data-flow engine. The following invariants are real but are **not** enforced by this gate
(spec §A.4 Tier 3):

- *"Every `@Transactional` mutating service method calls a `PermissionService.require*` guard
  **before** mutating."* ArchUnit can see that a class depends on `PermissionService`; it cannot
  prove per-method coverage or statement ordering.
- *"Never query `FeatureFlag` without joining `FlagEnvironmentState`."* A query-shape property, not
  a class-dependency one.
- *Taint analysis* (raw header values reaching a sink unvalidated). Needs a code property graph.

Tier 2 (immutable `FeatureFlag.key` via a field-set condition; `update()` must not read the
request's key) is deferred to a follow-up per spec §8 — Tier 1 stabilises first.

## Consequences

- **Good:** Seven invariants move from prose to build-gating assertions at zero infrastructure cost —
  no new service, no database, no CI change, Apache-2.0, test scope only. A violation is caught in
  `verify` rather than in review. The rules are also documentation that cannot go stale silently.
- **Good:** Verified by a negative test, not by assertion. Introducing a controller → repository
  dependency made `./mvnw verify` fail on R1 and R2 (255 other tests still passing); introducing a
  new package cycle made the *frozen* R7 fail without mutating the baseline store. Both were then
  reverted. This is the evidence the gate is live rather than vacuously green.
- **Good:** A review pass specifically hunting for *vacuous* rules — ones that pass because they
  match nothing — found two and both were closed before merge. R6 originally filtered on
  `haveSimpleNameEndingWith("Repository")`, so a hand-rolled DAO in the repository package under any
  other name sailed through; it now asserts over every type in the package. R4's
  `EvaluationController` exemption was dead. Each fix was confirmed with a probe that fails now and
  passed before. "The arch tests are green" is only meaningful if the rules can actually fire.
- **Bad:** A frozen rule is a rule with an asterisk. R7 gates new cycles but tolerates the recorded
  one; the baseline must be read as debt, not as a clean bill of health.
- **Bad:** Rules encode package names, so a package rename requires editing the test. Acceptable —
  that edit is exactly the moment to re-confirm the invariant still means what it said.
- **Neutral:** The frozen store is a text file whose entries include class/method signatures. It is
  matched ignoring line numbers, but a refactor of `SecurityConfig`'s wiring will legitimately
  invalidate the entry and require re-freezing (delete the store file and re-run).

## Alternatives Considered

- **Keep the invariants as prose + human review.** The status quo. Rejected: it has no failure
  mode — a broken invariant produces no signal at all.
- **jQAssistant 2.9.1 + embedded Neo4j, Cypher constraints bound to `verify`.** Strictly more
  powerful: it closes the Tier 3 gaps (per-method guard coverage, DI-aware layering) and the same
  store could serve agent queries via `mcp-neo4j-cypher`. Rejected *for now*: it means operating
  Neo4j and writing Cypher, for a 2-dev team whose Tier 1 rules were not yet enforced at all. This
  remains the documented upgrade path if Tier 3 governance becomes a felt need.
- **A custom Maven-enforcer / Checkstyle import-control ruleset.** Import-based checks are weaker
  than bytecode checks (they miss generics, annotations, and reflective class references — the R1
  negative test above tripped on exactly a generic type argument and a static class reference, both
  invisible to an import scan) and Checkstyle cannot express cycles or layered architectures.
- **Fail the build on the `config` ↔ `security` cycle immediately.** Rejected: it forces a
  refactor of security wiring as a precondition for having *any* architecture gate. Freezing lets
  the other six rules start protecting the codebase today.
