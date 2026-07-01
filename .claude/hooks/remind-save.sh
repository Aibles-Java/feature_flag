#!/usr/bin/env bash
# Stop hook — nudges Claude to run /save-memory, at most once per calendar day,
# and only if there's uncommitted work (a proxy for "real work happened this session").
set -euo pipefail

MEMORY_DIR="$CLAUDE_PROJECT_DIR/.claude/memory"
GUARD_FILE="$MEMORY_DIR/.nudge-guard"
TODAY="$(date +%F)"

if [ ! -d "$MEMORY_DIR" ]; then
  exit 0
fi

if [ -f "$GUARD_FILE" ] && [ "$(cat "$GUARD_FILE" 2>/dev/null)" = "$TODAY" ]; then
  exit 0
fi

cd "$CLAUDE_PROJECT_DIR"
if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  if [ -z "$(git status --porcelain 2>/dev/null)" ]; then
    exit 0
  fi
fi

echo "$TODAY" > "$GUARD_FILE"
echo "Reminder: this session has uncommitted changes. Consider running /save-memory to record decisions/handoff before stopping."
