#!/usr/bin/env bash
# security-review-gate.sh — Stop hook.
#
# CLAUDE.md mandates a security review before committing changes to the
# sensitive auth/crypto/migration surface. This hook makes that mandate visible:
# at Stop, if the working tree (staged + unstaged + committed-but-unpushed) has
# touched any sensitive path, it nudges to run /security-review.
#
# It fires at most once per calendar day per sensitive-file set change, using a
# guard file, so it does not nag on every Stop.
set -uo pipefail

PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(pwd)}"
GUARD_FILE="$PROJECT_DIR/.claude/memory/.security-gate-guard"

cd "$PROJECT_DIR" 2>/dev/null || exit 0
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || exit 0

# Sensitive paths (extended regex against git-reported file paths).
SENSITIVE='^(src/main/java/org/aibles/feature_flag/(security/|config/SecurityConfig\.java|util/ApiKeyGenerator\.java|util/ApiKeyHasher\.java)|src/main/resources/db/changelog/)'

# Files changed vs upstream (or origin/develop for a new branch) + working tree.
if upstream="$(git rev-parse --abbrev-ref --symbolic-full-name '@{upstream}' 2>/dev/null)"; then
  base="$upstream"
elif git rev-parse --verify origin/develop >/dev/null 2>&1; then
  base="origin/develop"
else
  base=""
fi

changed="$( { git diff --name-only 2>/dev/null; git diff --name-only --cached 2>/dev/null; \
             [ -n "$base" ] && git diff --name-only "${base}..HEAD" 2>/dev/null; } | sort -u )"

hits="$(printf '%s\n' "$changed" | grep -E "$SENSITIVE" || true)"
[ -z "$hits" ] && exit 0

# De-dupe the nudge: one fire per unique sensitive-file set per day.
sig="$(date +%F):$(printf '%s' "$hits" | shasum | cut -d' ' -f1)"
[ "$(cat "$GUARD_FILE" 2>/dev/null)" = "$sig" ] && exit 0
printf '%s' "$sig" > "$GUARD_FILE" 2>/dev/null || true

echo "Security-sensitive files changed this session:"
printf '  - %s\n' $hits
echo "CLAUDE.md requires a security review before committing these. Run /security-review (or /review-pr) before you push."
