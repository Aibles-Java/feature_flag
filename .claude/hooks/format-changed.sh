#!/usr/bin/env bash
# format-changed.sh — Stop hook. Reformats changed Java files with Spotless so
# the CI `spotless:check` gate stays green. Runs at Stop (not per-edit) to avoid
# paying Maven startup on every keystroke. Best-effort: never blocks the Stop.
set -uo pipefail

PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(pwd)}"
cd "$PROJECT_DIR" 2>/dev/null || exit 0
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || exit 0

# No JDK on PATH → skip silently (e.g. sandboxed runs).
"$PROJECT_DIR/mvnw" -v >/dev/null 2>&1 || exit 0

# Changed + staged Java files this session.
files="$( { git diff --name-only; git diff --name-only --cached; } 2>/dev/null \
          | grep -E '\.java$' | sort -u )"
[ -z "$files" ] && exit 0

# Spotless matches on a path regex; build an alternation of the changed files.
regex="$(printf '%s\n' $files | sed 's/[.[\*^$()+?{|]/\\&/g' | paste -sd'|' -)"

"$PROJECT_DIR/mvnw" -q spotless:apply "-DspotlessFiles=$regex" >/dev/null 2>&1 || true
exit 0
