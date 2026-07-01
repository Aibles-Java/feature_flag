# Wiring new hooks into .claude/settings.json needs explicit user confirmation

Claude Code's auto-mode classifier blocks edits that register new auto-executing
`SessionStart`/`Stop`/`PostToolUse` hooks in `.claude/settings.json` when the request behind
them is vague (e.g. a general "seems like X is missing" remark rather than an explicit ask
for that exact config change). It's treated as a self-modification risk since hooks run
scripts automatically on every future session.

**How to apply:** when adding or changing hooks in this repo's `.claude/settings.json`, ask
the user to explicitly confirm the specific hook(s) being wired in before editing — don't
assume approval carries over from an earlier, broader request (e.g. "set up Tier 3" does not
imply "also auto-wire this specific hook" without a follow-up confirm).
