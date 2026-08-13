# 0023 — ArchUnit Tier-2: immutable flag key (issue #49)

**Date:** 2026-08-13
**Status:** Implemented on `feature/issue-49-archunit-tier2`, **stacked on**
`feature/issue-48-archunit-governance` (PR #65) — Tier 2 adds rules to the `ArchitectureTest` that
Tier 1 creates, so it cannot branch from `develop`. Extends
[0022 — ArchUnit Tier-1 governance gate](0022-archunit-tier1-governance-gate.md).

## The headline: Lombok makes the spec's Tier-2 rules VACUOUS

Both conditions issue #49 asks for, taken literally, can never fail. Verified in bytecode, not
guessed:

1. **"No method sets `FeatureFlag.key` except the construction/builder path."** `key` is `private`,
   and Lombok's `@Setter` generates `setKey` **inside** `FeatureFlag`. `javap -c` on the compiled
   entity shows exactly two `putfield key` sites, both in `FeatureFlag` itself (`setKey` and the
   `@AllArgsConstructor`). No other class *can* write the field — Java access control already
   guarantees what the rule would assert. **A field-set rule here asserts a tautology.** The
   reachable mistake is `flag.setKey(...)`, which in bytecode is an `invokevirtual`, not a
   `putfield` — a field-access rule looks straight past it.
2. **"`update()` must not call `UpdateFeatureFlagRequest.getKey()`."** That DTO has no `key` field
   (`javap`: only `getName`/`getDescription`), so `getKey()` does not exist. You cannot forbid a
   call to a method that isn't there, and you cannot even write the rule against a typed reference.

**Generalised lesson:** when a rule targets a Lombok-generated member, work out *where the bytecode
actually lands* before writing the assertion. `@Setter`/`@Data` move the write inside the owning
class, which silently converts a field-access rule into a no-op. Same trap family as the R6/R4
vacuity found in [[0022-archunit-tier1-governance-gate]] — the failure mode of an arch gate is
almost never a wrong rule, it's a rule that cannot fire.

## What shipped instead

- **R8** — a custom `ArchCondition<JavaClass>` iterating `getFieldAccessesFromSelf()` (SET on
  `FeatureFlag.key`) **and** `getMethodCallsFromSelf()` (target `FeatureFlag.setKey`), applied via
  `noClasses().that().doNotHaveFullyQualifiedName(FEATURE_FLAG).should(condition)`. The method-call
  half is what actually fires; the field half is a backstop for a future hand-written mutator or
  nested class. With `noClasses().should(cond)`, ArchUnit inverts the condition — so inside
  `check()` you add `SimpleConditionEvent.satisfied(...)` for each *detection*. Adding `violated`
  events there is the easy way to build a rule that never reports anything.
- **R9** — `noFields().that().areDeclaredInClassesThat().haveFullyQualifiedName(UpdateFeatureFlagRequest)
  .should().haveName("key")`. Forbidding the *field* is the sound form of "update ignores the key":
  reintroducing it is the first step of the mistake, and with `@Data` it immediately produces
  `getKey()`, JSON binding, and a plausible `if (request.getKey() != null)` one edit away.

Both proven by negative test then reverted: a `flag.setKey("oops")` in `FeatureFlagServiceImpl
.update()` fails R8 with the exact call site and line number; adding `private String key;` to
`UpdateFeatureFlagRequest` fails R9.

## Why this rule is worth having at all

`@Column(updatable = false)` means an accidental `setKey(...)` is **silently dropped at flush
time** — no exception, no log. The in-memory entity disagrees with the row, and nothing surfaces
until an SDK notices the key it caches no longer matches. R8 converts that invisible failure into
a build failure.

## Note for whoever merges

`./mvnw verify`: 259 tests, `ArchitectureTest` 9/9, Spotless + JaCoCo green. The PR is based on the
Tier-1 branch, so it must merge **after** PR #65; rebase onto `develop` if #65 lands first.
