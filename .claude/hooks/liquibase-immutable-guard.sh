#!/usr/bin/env bash
# liquibase-immutable-guard.sh — PreToolUse(Edit|Write|MultiEdit) hook.
#
# Enforces the repo rule (CLAUDE.md + memory convention): a Liquibase changeset
# that already exists must NEVER be modified — always add a NEW migration file.
#
# Blocks (exit 2) when the tool would edit/overwrite a file that already exists
# under src/main/resources/db/changelog/migrations/. Creating a brand-new
# migration file is allowed (the path does not exist yet). Editing the master
# changelog (db.changelog-master.xml) is allowed — that's how new changesets get
# registered.
#
# Exit 2 → Claude Code surfaces stderr back to the model as the block reason.
set -uo pipefail

MIGRATIONS_GLOB='src/main/resources/db/changelog/migrations/'

input="$(cat)"
path="$(printf '%s' "$input" | python3 -c 'import sys,json
try:
    print(json.load(sys.stdin).get("tool_input",{}).get("file_path",""))
except Exception:
    print("")' 2>/dev/null || echo "")"

[ -z "$path" ] && exit 0

# Only care about files inside the migrations directory.
case "$path" in
  *"$MIGRATIONS_GLOB"*) ;;
  *) exit 0 ;;
esac

# Brand-new migration file (does not exist yet) → allow.
[ -e "$path" ] || exit 0

cat >&2 <<EOF

  ===== LIQUIBASE GUARD: edit blocked =====
  $path
  is an existing Liquibase changeset. Already-run changesets are IMMUTABLE —
  editing one desyncs the schema from every environment that already applied it.

  Instead: add a NEW migration file (e.g. 0NN-<change>.xml) and register it in
  db.changelog-master.xml.

  Genuinely need to touch this file (pre-merge changeset never run anywhere)?
  Make the change by hand outside this tool call.
  =========================================
EOF
exit 2
