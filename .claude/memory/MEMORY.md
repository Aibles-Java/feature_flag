# Memory Index — feature_flag

*Loaded automatically at the start of every session. Keep entries to one line each.
Updated by `/save-memory`. See `README.md` for how this system works.*

<!-- Format: - [Title](path) — one-line hook. Newest relevant entries near the top. -->

- [Stop-hook nudge needs commit tracking](conventions/stop-hook-nudge-needs-commit-tracking.md) — dirty-tree-only check went silent for commit-before-stop sessions; also gitignore hook state files
- [Release flow in git-workflow skill](decisions/0003-release-flow-in-git-workflow-skill.md) — develop→release→main process added as step-gated instructions, not a new skill
- [PR template + create-pr skill](decisions/0002-pr-template-and-create-pr-skill.md) — fixed 6-section PR format; requires `gh` CLI installed + authenticated locally
- [Claude Code harness setup](decisions/0001-claude-code-harness-setup.md) — Tier 3 harness: gitflow + conventional commits, workflow gates, sensitive areas, hand-authored memory lifecycle
- [Hook changes need explicit confirm](conventions/hook-changes-require-explicit-confirmation.md) — auto-mode blocks silently wiring new hooks into settings.json
