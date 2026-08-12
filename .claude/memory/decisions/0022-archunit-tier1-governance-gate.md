# 0022 — ArchUnit Tier-1 governance gate (issue #48)

**Date:** 2026-08-12
**Status:** Implemented on `feature/issue-48-archunit-governance`. Implements Goal A of
[0014 — code graph adoption](0014-codegraph-adoption.md); spec `docs/specs/codegraph-adoption.md`
§A. ADR: `docs/adr/ADR-0004-archunit-architecture-governance.md`.

## Decision

`archunit-junit5:1.4.2` (test scope, version pinned via a new `archunit.version` property) +
`src/test/java/org/aibles/feature_flag/architecture/ArchitectureTest.java` with rules R1–R7.
**No plugin or CI change** — ArchUnit rules are plain JUnit 5 tests, so Surefire already runs them
in `./mvnw verify`, next to Spotless and the JaCoCo ratchet.

## The three places the spec's §A.4 code sketch did NOT survive contact with the code

The sketch was written against the repo as of 2026-07-10; `metrics`, `logging`, `notification`,
`audit` and `security.ratelimit` landed since. Copying it verbatim does **not** work:

1. **`accessClassesThat()` does not exist on `ClassesThat`** in ArchUnit 1.4.2 — the spec's R4
   (`classes().that().accessClassesThat()...`) is a **compile error**. The working form is inverted:
   `noClasses().that().doNotHaveFullyQualifiedName(A).and().doNotHaveFullyQualifiedName(B)
   .and().resideOutsideOfPackage("..security..").should().accessClassesThat().haveFullyQualifiedName(SCH)`.
   (`ClassesShould.accessClassesThat()` exists; `ClassesThat.accessClassesThat()` does not.
   `javap` on the archunit jar is the fastest way to settle this.)
2. **R1 needs `Security` + `Config` declared as layers.** `mayOnlyBeAccessedByLayers("Service")`
   alone fails: `ApiKeyAuthenticationFilter` and `CustomUserDetailsService` read repositories to
   resolve the principal *before* any service exists, and `SecurityConfig` injects
   `EnvironmentRepository` into the filter. This is a fact about auth, not a shortcut. **Classes
   outside every declared layer still count as violations** in `layeredArchitecture()` — you must
   declare a layer or `ignoreDependency`, you cannot just omit them.
3. **R4 must allow the whole `..security..` package**, not only `PermissionService` +
   `EvaluationController`. The filters' *job* is to populate the holder, and ArchUnit sees a static
   method call — it **cannot distinguish a read from a write**. The invariant that actually
   survives: no service/other-controller/support package does an ad-hoc, cast-unsafe principal read.

## R7 is frozen — and the freeze is configured, not defaulted

One pre-existing cycle: `config` → `security` (`SecurityConfig` constructs the filters) and
`security` → `config` (`JwtTokenProvider` reads `JwtProperties`). Breaking it means relocating the
JWT properties — a sensitive-area refactor that does not belong in a governance PR. So R7 is
`FreezingArchRule.freeze(...)` with a **committed** baseline.

- `src/test/resources/archunit.properties` sets `freeze.store.default.path=src/test/resources/archunit_store`
  (default would be `archunit_store/` at the repo root, uncommitted), `allowStoreCreation=true`, and
  **`freeze.refreeze=false` explicitly** — otherwise a new violation silently widens the baseline
  instead of failing.
- **Gotcha:** if the store is missing, a frozen rule *creates* it and **passes**. A frozen rule with
  no committed store is a rule that does nothing. The store must be in git.
- Store filename is a random UUID mapped by `stored.rules`; matching ignores line numbers, so
  ordinary edits above `SecurityConfig` don't invalidate it, but a real rewiring refactor will
  (delete the store file, re-run to re-freeze).

## The negative test is the deliverable, not a formality

A green arch test proves nothing on its own — it may be vacuous. Both directions were proven, then
reverted:

- **Controller → repository:** `./mvnw verify` → BUILD FAILURE on R1 **and** R2 (255 other tests
  still green). **Do this with a `private static final Class<XRepository> ...` reference, not an
  injected field** — a `private final` field is picked up by Lombok `@RequiredArgsConstructor`,
  which changes the constructor signature and breaks **test compilation before the arch rule ever
  runs**. That failure looks like a passing gate but isn't one.
- **New package cycle** (a `util` class referencing `service.impl`): frozen R7 **failed** with the
  new cycle, and the store's md5 was **unchanged** — proof `refreeze=false` holds and that freezing
  did not neuter the rule.
- ArchUnit caught the violation via a *generic type argument* and a *static class reference* —
  neither of which an import-based lint (Checkstyle import-control) would see. That's the concrete
  argument for bytecode analysis over import scanning.

## Review pass: the failure mode of an arch gate is a VACUOUS rule, not a wrong one

A rule that matches zero classes passes forever and looks identical to a rule that works. Reviewing
specifically for this found two, both fixed pre-merge, each confirmed by a probe that fails now and
passed before:

- **R6 was name-filtered** (`haveSimpleNameEndingWith("Repository")`), so a hand-rolled DAO in
  `..repository..` named anything else was invisible — the exact thing the rule exists to catch.
  Now asserts over **every** type in the package. Known consequence, documented in the javadoc: a
  Spring Data custom fragment (`XRepositoryImpl`) will trip it. That's intended friction.
- **R4's `EvaluationController` exemption was dead.** The SDK controller takes an injected
  `Authentication` **method parameter** and never touches `SecurityContextHolder`. Dropped the
  allowance → R4 got strictly tighter. **`CLAUDE.md` was wrong here** and was corrected: it claimed
  the `Environment` "is available via `SecurityContextHolder` in `EvaluationController`". Prose in
  the SSOT had drifted from the code; the arch rule copied from the prose inherited the drift.

**`allowStoreCreation=true` is a vacuous-gate hazard — now set to `false`.** With it true, a
*missing* store makes ArchUnit silently create a new baseline from current code and report GREEN
(verified: store moved aside → `mvn test` exit 0, fresh baseline written, no warning). The gate
passes exactly when it has lost its baseline. With it false you get a loud
`StoreInitializationFailedException`. Re-freeze procedure (flip true → delete store → run → flip
back → commit) is written into `archunit.properties`. Related: `.gitattributes` now pins
`src/test/resources/archunit_store/** text eol=lf`, since the store is matched by line content and
`core.autocrlf=true` was rewriting it to CRLF on Windows checkout (harmless in practice — ArchUnit
reads via `readLine()` — but not worth leaving to chance).

Also confirmed by review: `freeze.refreeze=false` is ArchUnit's own default (belt-and-braces, kept
for intent), and the store path is resolved against Surefire's working directory, which defaults to
the module basedir — reliable for CLI and CI, but an IDE run-config rooted elsewhere is the one way
to break it.

## Scope honesty (do not overclaim)

Tier 3 stays unenforced and is documented as such in the ADR + test javadoc: per-method
`PermissionService.require*` guard coverage and ordering, "never query `FeatureFlag` without joining
`FlagEnvironmentState`", and taint analysis. ArchUnit sees dependency/access **edges**, not data
flow. Tier 2 (immutable `FeatureFlag.key` via a field-set condition) is a deliberate follow-up.

## Numbering collisions found

- Issue #48 and the spec both say "ADR-0003" — **taken** by pagination. Shipped as **ADR-0004**,
  with a reconciliation note in the ADR. `docs/adr/README.md` index was also missing ADR-0003; both
  rows added.
- This memory file is **0022**, skipping 0021 which is in flight on the unmerged
  `feature/issue-38-env-clone-import-export` branch.
