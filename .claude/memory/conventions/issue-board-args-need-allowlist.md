# issue-board.sh: CLI args must be allow-list validated before reaching jq filters

**Discovered:** 2026-07-02 (issue #17 code review, MEDIUM finding)

`issue-board.sh` builds jq filters by string interpolation, e.g.
`select(.name==\"$2\")`. That is safe for the original subcommands because every
interpolated value is a hardcoded literal (`"In progress"`, `"Done"`, …) — but the
moment a subcommand feeds a **raw CLI argument** into that function family, a value
containing `"` breaks the jq filter and the script dies with a raw jq parse error
instead of a clean `die()` (verified with `XS") or (true`).

**Why:** `set -euo pipefail` turns any malformed jq filter into an abrupt,
message-less exit; worse, a crafted value could in principle alter the filter's
logic.

**How to apply:** any new subcommand that passes user input into `field_id()` /
`option_id()` / a jq `-q` string must first validate it against an explicit
allow-list, the way `estimate` does:

```bash
[[ "$size" =~ ^(XS|S|M|L|XL)$ ]] || die "size must be one of XS|S|M|L|XL (got '$size')"
```

Numbers get a format regex **plus** a `> 0` check (`^0+([.]0+)?$` → reject).

Related: [[shared-board-repo-scoping]] (the other invariant when extending this script).
