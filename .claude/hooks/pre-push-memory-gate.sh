#!/usr/bin/env bash
# pre-push-memory-gate.sh — refuse to push work that ships WITHOUT saved memory.
#
# Two invocation modes:
#   claude : Claude Code PreToolUse hook on Bash. Reads the tool-call JSON on
#            stdin, only acts when the command is a `git push`, and exits 2 to
#            block (Claude Code surfaces stderr back to the model as the reason).
#   git    : git `pre-push` backstop (see .githooks/pre-push). Always checks;
#            exits 1 to abort the push for a human too.
#
# Rule: look at the commits about to be pushed (upstream..HEAD). If they touch
# files OUTSIDE .claude/memory/ but NONE of them touch .claude/memory/, the
# session is shipping work without recording memory → block and tell the caller
# to run /save-memory first so memory travels with the work.
#
# Escape hatch: SKIP_MEMORY_CHECK=1 bypasses the gate (for intentional
# memory-less pushes, e.g. pushing a pure docs typo fix).
set -uo pipefail

MODE="${1:-git}"
PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"

# In claude mode, only engage for actual `git push` commands.
if [ "$MODE" = "claude" ]; then
  BLOCK_CODE=2
  input="$(cat)"
  cmd="$(printf '%s' "$input" | python3 -c 'import sys,json;
try:
    print(json.load(sys.stdin).get("tool_input",{}).get("command",""))
except Exception:
    print("")' 2>/dev/null || echo "")"
  # Not a git push → allow silently.
  echo "$cmd" | grep -Eq '(^|[;&|]|[[:space:]])git([[:space:]]|.*[[:space:]])push(\b|[[:space:]]|$)' || exit 0
  # Explicit override present in the command → allow.
  echo "$cmd" | grep -q 'SKIP_MEMORY_CHECK=1' && exit 0
else
  BLOCK_CODE=1
fi

# Global escape hatch.
[ "${SKIP_MEMORY_CHECK:-}" = "1" ] && exit 0

cd "$PROJECT_DIR" 2>/dev/null || exit 0
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || exit 0

# Determine the commit range that would be pushed.
if upstream="$(git rev-parse --abbrev-ref --symbolic-full-name '@{upstream}' 2>/dev/null)"; then
  range="${upstream}..HEAD"
elif git rev-parse --verify origin/develop >/dev/null 2>&1; then
  range="origin/develop..HEAD"   # new branch not yet tracking a remote
else
  exit 0                          # can't determine a base → don't block
fi

changed="$(git diff --name-only "$range" 2>/dev/null || true)"
[ -z "$changed" ] && exit 0        # nothing new to push

# Memory updated as part of this push → good, allow.
if printf '%s\n' "$changed" | grep -q '^\.claude/memory/'; then
  exit 0
fi

# Work is being pushed with no memory update in the range → block.
cat >&2 <<EOF

  ===== MEMORY GATE: push blocked =====
  This push ($range) contains work commits but NONE of them update
  .claude/memory/ — session context would ship without being recorded.

  Do this first:
    1. Run /save-memory   (updates HANDOFF.md, MEMORY.md, decisions/…)
    2. Commit the memory changes
    3. Push again — memory now travels with the work.

  Intentional memory-less push? Override with:
    SKIP_MEMORY_CHECK=1 git push …
  =====================================
EOF
exit "$BLOCK_CODE"
