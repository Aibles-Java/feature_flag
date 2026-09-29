---
name: stale-issue-scope-verify-before-implementing
description: issue bodies in this repo go stale — work often lands via a side branch without closing the issue; grep the code for every scope bullet before implementing anything
metadata:
  type: convention
---

# Verify an issue's scope against the code before implementing

Issue bodies here are written once and not revised, while work sometimes lands through a
differently-named branch that never closes the issue. So an issue can read as entirely unimplemented
when most of it already shipped.

**Issue #35** (percentage rollout) was the clear case: its scope listed "add `rollout_percent` column
via Liquibase", "add `identifier` param", "invoke `RolloutEvaluator`" — all four code bullets were
already on `develop`, landed via `feat/rollout-percent` (`7daed49`, `cd666ee`). Implementing the issue
as written would have meant re-adding an existing column in a new changeset. What was genuinely
missing was narrower and different in kind: the tests its acceptance criteria demanded (there was no
`RolloutEvaluatorTest` at all), the contract documentation, and a latent bug.

## How to check, cheaply

Before writing code, turn each scope bullet into a grep:

```bash
ls src/main/resources/db/changelog/migrations/          # does the migration already exist?
grep -rn '<fieldName>' --include='*.java' src/main      # entity / DTO / service wiring
grep -rn '<ClassName>' --include='*.java' src/          # is the utility actually called, or dead?
ls src/test/java/.../<area>/                            # do the AC's tests exist?
git log --oneline origin/develop -- <path>              # how and when did it land
```

Then report the delta — implemented vs. missing — instead of restating the issue. The remaining work
is usually tests, documentation and edge cases: the parts a "make it work" pass skips, and the parts
the acceptance criteria actually name.

## Related

An issue being open is not evidence that nothing shipped, and an issue being assigned is not evidence
that someone is working on it. Check `git log` on the touched paths.
