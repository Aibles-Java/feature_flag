# Decision comments: cross-issue `gh` writes are blocked in auto mode

**Context (issue #15, 2026-07-02).** The `issue-workflow` skill now tells CC to post a
templated, sanitized "Decision" comment to the linked issue after a substantive
human-in-the-loop decision resolves.

**Gotcha discovered while verifying:** the Claude Code auto-mode permission classifier
**denies `gh issue comment` against any issue other than the one currently being
worked** (an attempted retroactive Decision comment to #4, made while working #15, was
blocked as an external-system write unrelated to the task).

**How to apply:**
- Decision comments can only target the issue linked to the *current* work (branch
  `feature/issue-N-…`) — which is exactly what the convention prescribes anyway.
- Retroactive or cross-issue comments need the human to approve the specific command
  (or an allowlist rule in settings); don't retry them unprompted in auto mode.
- Use the quoted-heredoc `--body "$(cat <<'EOF' … EOF)"` pattern (as documented in the
  skill) so apostrophes in free-text rationales don't break the shell command.
