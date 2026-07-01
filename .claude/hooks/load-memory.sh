#!/usr/bin/env bash
# SessionStart hook — injects project memory (MEMORY.md + HANDOFF.md) into context.
set -euo pipefail

MEMORY_DIR="$CLAUDE_PROJECT_DIR/.claude/memory"

if [ ! -d "$MEMORY_DIR" ]; then
  exit 0
fi

echo "## Project memory (.claude/memory/) — read only files listed under 'Context to Load' below, lazily"
echo

if [ -f "$MEMORY_DIR/MEMORY.md" ]; then
  cat "$MEMORY_DIR/MEMORY.md"
  echo
fi

if [ -f "$MEMORY_DIR/HANDOFF.md" ]; then
  cat "$MEMORY_DIR/HANDOFF.md"
fi
