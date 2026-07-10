# Spotless file-scoping + macOS bash gotchas (formatting hooks)

Two traps hit while wiring the Spotless auto-format hooks (`spotless-staged.sh`
pre-commit, `format-changed.sh` Stop hook). Both failed *silently* — the hook
exited 0 and formatted nothing.

## 1. `-DspotlessFiles` matches the ABSOLUTE path (full-match)
`spotless:apply -DspotlessFiles=<regex>` matches each candidate against its
**absolute** path, and it behaves like a full match — a **relative** regex
(`src/test/java/.../Foo.java`) matches nothing, so Spotless reports "clean" and
changes zero files. Fix: prefix the repo root, e.g.
`sed "s#^#$ROOT/#"` before escaping, so the regex is
`/Users/.../feature_flag/src/test/java/.../Foo\.java`. Pipe multiple files with
`paste -sd'|' -`.

## 2. macOS system bash is 3.2 — no `mapfile`/`readarray`
`.githooks/*` run under `/usr/bin/env bash` → `/bin/bash` = **Bash 3.2** on macOS.
`mapfile`/`readarray` are Bash 4+ and fail with "command not found" +
`set -u` "unbound variable". Keep hook scripts array-free: capture with
`x="$(git … )"`, iterate with `printf '%s\n' "$x" | while IFS= read -r f`.
(Process substitution `<(…)` and `comm` are fine in 3.2.)

## Testing lesson
"staged == working tree" is NOT proof the formatter ran — if it no-ops, staged and
working are trivially equal (both unformatted). Assert on actual content: the
reformatted file must differ from the misformatted input, and check the **staged
blob** with `git show ":path"`, not just the working file.

Related: [[0012-harness-guards-spotless-coverage]], [[stop-hook-nudge-needs-commit-tracking]].
