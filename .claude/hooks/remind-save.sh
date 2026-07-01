#!/usr/bin/env bash
# Stop hook — nudges Claude to run /save-memory, at most once per calendar day,
# and only if something changed since the last check: a dirty tree OR new
# commits (a proxy for "real work happened this session"). Dirty-tree alone
# isn't enough — sessions that commit+push before stopping would otherwise
# never trigger the nudge.
set -euo pipefail

MEMORY_DIR="$CLAUDE_PROJECT_DIR/.claude/memory"
GUARD_FILE="$MEMORY_DIR/.nudge-guard"
LAST_SHA_FILE="$MEMORY_DIR/.last-seen-commit"
TODAY="$(date +%F)"

if [ ! -d "$MEMORY_DIR" ]; then
  exit 0
fi

if [ -f "$GUARD_FILE" ] && [ "$(cat "$GUARD_FILE" 2>/dev/null)" = "$TODAY" ]; then
  exit 0
fi

cd "$CLAUDE_PROJECT_DIR"
if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  DIRTY=false
  [ -n "$(git status --porcelain 2>/dev/null)" ] && DIRTY=true

  CURRENT_SHA="$(git rev-parse HEAD 2>/dev/null || echo "")"
  LAST_SHA="$(cat "$LAST_SHA_FILE" 2>/dev/null || echo "")"
  echo "$CURRENT_SHA" > "$LAST_SHA_FILE"

  if [ "$DIRTY" = false ] && [ "$CURRENT_SHA" = "$LAST_SHA" ]; then
    exit 0
  fi
fi

echo "$TODAY" > "$GUARD_FILE"
echo "Reminder: this session has uncommitted changes or new commits since the last check. Consider running /save-memory to record decisions/handoff before stopping."
