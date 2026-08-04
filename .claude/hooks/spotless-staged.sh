#!/usr/bin/env bash
# spotless-staged.sh — auto-format staged Java files with Spotless before commit.
#
# Invoked by .githooks/pre-commit. Keeps every commit consistent with the
# google-java-format rule enforced by `spotless:check` in CI, regardless of which
# editor the committer uses (VS Code / IntelliJ / none). Editor-agnostic, so it's
# the real consistency guarantee for a mixed-editor team.
#
# Behaviour: reformats the STAGED .java files in place, then re-stages them.
# Best-effort — never hard-fails the commit on tooling problems (no JDK, mvnw
# error): it warns and lets the commit through, because CI's spotless:check is
# the backstop. Bypass a single commit with:  SKIP_SPOTLESS=1 git commit …
#
# Written for POSIX-ish Bash 3.2 (macOS system bash) — no mapfile/readarray.
set -uo pipefail

[ "${SKIP_SPOTLESS:-}" = "1" ] && exit 0

ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || exit 0
cd "$ROOT" || exit 0

# Staged, still-present Java files (Added/Copied/Modified — not Deleted).
staged="$(git diff --cached --name-only --diff-filter=ACM -- '*.java')"
[ -z "$staged" ] && exit 0

# No runnable Maven/JDK → skip (CI still gates). Matches the other hooks.
if ! ./mvnw -v >/dev/null 2>&1; then
  echo "spotless-staged: no runnable ./mvnw (JDK missing?) — skipping format; CI will still check." >&2
  exit 0
fi

# Warn if any staged file also has UNSTAGED edits: re-staging after format pulls
# those unstaged changes into the commit too. Rare; surface rather than swallow.
unstaged="$(git diff --name-only -- '*.java')"
partial="$(comm -12 <(printf '%s\n' "$staged" | sort) <(printf '%s\n' "$unstaged" | sort) 2>/dev/null || true)"
if [ -n "$partial" ]; then
  echo "spotless-staged: NOTE — these staged files also had unstaged edits; formatting will stage the whole file:" >&2
  printf '  - %s\n' $partial >&2
fi

# Scope Spotless to just the staged files. NOTE: spotlessFiles matches (full-match)
# against the ABSOLUTE file path — a relative regex matches nothing. Prefix $ROOT.
regex="$(printf '%s\n' "$staged" | sed "s#^#$ROOT/#" | sed 's/[.[\*^$()+?{|]/\\&/g' | paste -sd'|' -)"

if ! ./mvnw -q spotless:apply "-DspotlessFiles=$regex" >/dev/null 2>&1; then
  echo "spotless-staged: spotless:apply failed — committing as-is; fix with './mvnw spotless:apply'." >&2
  exit 0
fi

# Re-stage the (possibly) reformatted files so the commit contains formatted code.
# One-by-one keeps paths with spaces intact (Java paths rarely have them, but safe).
printf '%s\n' "$staged" | while IFS= read -r f; do
  [ -n "$f" ] && git add -- "$f"
done
exit 0
