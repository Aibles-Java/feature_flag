# Memory Index — feature_flag

*Loaded automatically at the start of every session. Keep entries to one line each.
Updated by `/save-memory`. See `README.md` for how this system works.*

<!-- Format: - [Title](path) — one-line hook. Newest relevant entries near the top. -->

- [Secrets externalization: bind-time validation + no-default prod profile](decisions/0008-secrets-externalization-fail-fast.md) — issue #23: JwtProperties bean validation fails startup on missing/short/placeholder/low-entropy secret; prod profile file has NO defaults so dev values can't leak; Dockerfile bakes SPRING_PROFILES_ACTIVE=prod
- [@ConfigurationProperties validation gotchas (Boot 4.1)](conventions/springboot-configprops-binding-gotchas.md) — binder passes unresolved `${VAR}` through as literals (unlike @Value); @AssertTrue only fires on getter-shaped isXxx() methods on records; @AssertTrue getters keep secrets out of the bind-failure report (field constraints echo the value)
- [JWT filter: catch UsernameNotFoundException + JwtException; warn not debug](conventions/jwt-filter-catch-scope.md) — issue #10: two separate parseSignedClaims() calls create a TOCTOU gap; catch both; valid-token/missing-subject is log.warn not log.debug
- [Estimation = /estimate-issue skill, hours-calibrated](decisions/0007-estimate-issue-skill.md) — issue #17: rubric XS≤1h…XL>16h→split, propose→confirm→write via `issue-board.sh estimate`, calibration log in the skill dir; 0006 is taken by the parked issue-14 branch
- [issue-board.sh args need allow-lists](conventions/issue-board-args-need-allowlist.md) — issue #17: raw CLI args interpolated into jq filters break on `"` — validate against an explicit allow-list (like `estimate` does for SIZE) before calling field_id/option_id
- [Decision comments: cross-issue gh writes blocked](conventions/decision-comments-cross-issue-blocked.md) — issue #15: auto-mode classifier denies `gh issue comment` on any issue other than the one being worked; decision comments target the current branch's issue only; use quoted-heredoc for `--body`

- [Shared board → scope by repo](conventions/shared-board-repo-scoping.md) — issue #12: the "Digital banking" board is multi-repo, so `issue-board.sh` must match cards by `.content.repository` + number (number alone moved another repo's card); pass `--limit 200`
- [Spring Boot 4 security-testing gotchas](conventions/springboot4-security-testing.md) — issue #4: `@AutoConfigureMockMvc` gone in Boot 4.1 (build MockMvc via `webAppContextSetup(ctx).apply(springSecurity())`); don't tamper JWTs by flipping the last sig char (padding bits → flaky) — splice a foreign payload instead
- [Issue-workflow: board status + memory gate](decisions/0005-issue-workflow-board-and-memory-gate.md) — issue #8: `issue-board.sh` assign/status on Digital banking board + pre-push gate that blocks code pushes lacking `.claude/memory/` changes; `gh` needs `project` scope
- [JaCoCo coverage ratchet + CI gate](decisions/0004-jacoco-coverage-ratchet-and-ci.md) — issue #3: JaCoCo on `verify` + CI, threshold starts at 0.00 ratchet (not 80%) since coverage is ~0%
- [Stop-hook nudge needs commit tracking](conventions/stop-hook-nudge-needs-commit-tracking.md) — dirty-tree-only check went silent for commit-before-stop sessions; also gitignore hook state files
- [Release flow in git-workflow skill](decisions/0003-release-flow-in-git-workflow-skill.md) — develop→release→main process added as step-gated instructions, not a new skill
- [PR template + create-pr skill](decisions/0002-pr-template-and-create-pr-skill.md) — fixed 6-section PR format; requires `gh` CLI installed + authenticated locally
- [Claude Code harness setup](decisions/0001-claude-code-harness-setup.md) — Tier 3 harness: gitflow + conventional commits, workflow gates, sensitive areas, hand-authored memory lifecycle
- [Hook changes need explicit confirm](conventions/hook-changes-require-explicit-confirmation.md) — auto-mode blocks silently wiring new hooks into settings.json
