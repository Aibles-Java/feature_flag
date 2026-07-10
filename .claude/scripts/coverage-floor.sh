#!/usr/bin/env bash
# coverage-floor.sh — measure current JaCoCo line coverage and (optionally)
# ratchet the pom.xml floor up to it.
#
# The ratchet gate already exists in pom.xml (jacoco:check, BUNDLE LINE ratio
# >= ${jacoco.line.coverage}). Today that floor is 0.00 (non-blocking). This
# script computes the real number so you can lock it in — the floor can then
# only go UP, never silently regress.
#
# Usage:
#   .claude/scripts/coverage-floor.sh          # measure + print, no changes
#   .claude/scripts/coverage-floor.sh --apply  # measure + rewrite pom floor
#
# Requires Java 21 + the JaCoCo report (this script runs `verify` to be sure).
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
APPLY="${1:-}"
CSV="target/site/jacoco/jacoco.csv"

echo "Running tests + JaCoCo report..."
./mvnw --batch-mode --no-transfer-progress -q verify -Djacoco.line.coverage=0.00

[ -f "$CSV" ] || { echo "ERROR: $CSV not found — did the report generate?"; exit 1; }

# Sum LINE_COVERED / (LINE_COVERED + LINE_MISSED) across the CSV, excluding the
# same classes the pom's check gate excludes (app entrypoint, MapStruct impls).
ratio="$(awk -F, '
  NR>1 && $3 != "FeatureFlagApplication" && $3 !~ /MapperImpl$/ {
    missed += $8; covered += $9
  }
  END {
    if (covered+missed == 0) { print "0.00"; exit }
    printf "%.2f", covered/(covered+missed)
  }' "$CSV")"

echo ""
echo "Current line coverage (gate-eligible classes): $ratio"

# Suggest a floor slightly below current to tolerate minor churn.
floor="$(awk -v r="$ratio" 'BEGIN { f = r - 0.02; if (f < 0) f = 0; printf "%.2f", f }')"
echo "Suggested ratchet floor (current - 0.02): $floor"

if [ "$APPLY" = "--apply" ]; then
  # Portable in-place edit of the property value.
  perl -0pi -e "s{(<jacoco\.line\.coverage>)[0-9.]+(</jacoco\.line\.coverage>)}{\${1}$floor\${2}}" pom.xml
  echo ""
  echo "pom.xml updated: <jacoco.line.coverage>$floor</jacoco.line.coverage>"
  echo "Commit it, then run './mvnw verify' to confirm the gate passes."
else
  echo ""
  echo "Dry run. Re-run with --apply to write the floor into pom.xml."
fi
