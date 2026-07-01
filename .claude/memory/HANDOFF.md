# Handoff

*Ephemeral — overwritten by `/save-memory` at the end of each session. Read this first.*

## Current WIP

Nothing in progress. PR #2 (`feature/release-flow-skill` → `develop`) is open awaiting
review/merge: https://github.com/Aibles-Java/feature_flag/pull/2

It contains:
- Release/hotfix flow added to the `git-workflow` skill (step-gated, STOPs before
  merge-to-main / tag / merge-back)
- `docs/adr/ADR-0002-release-process.md`
- Fix to `.claude/hooks/remind-save.sh` (now fires on new commits, not just a dirty
  tree) + `.gitignore` entries for its state files

PR #1 (`feature/claude-harness-setup` → `develop`) from the prior session is still open,
unmerged.

## Context to Load

- `decisions/0003-release-flow-in-git-workflow-skill.md` — if asked about the release
  process or why it's in `git-workflow` vs. a separate skill
- `conventions/stop-hook-nudge-needs-commit-tracking.md` — before touching
  `remind-save.sh` again, or if the save-memory reminder seems to misfire
- `decisions/0001-claude-code-harness-setup.md` — if asked about harness config choices
- `decisions/0002-pr-template-and-create-pr-skill.md` — if asked about PR format or `create-pr`
- `conventions/hook-changes-require-explicit-confirmation.md` — before editing
  `.claude/settings.json` hooks or hook scripts under `.claude/hooks/`

## Next steps

- Review/merge PR #1 (`feature/claude-harness-setup` → `develop`) — still open from prior session
- Review/merge PR #2 (`feature/release-flow-skill` → `develop`)
- Optional: install `shipwithai-java-backend-toolkit` plugin via `/plugin`, then re-run
  `/shipwithai-starter:init --update` to wire it in
