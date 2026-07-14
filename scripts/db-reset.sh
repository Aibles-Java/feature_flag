#!/usr/bin/env bash
# scripts/db-reset.sh — fast, non-destructive reset of the feature_flag dev DB.
#
# Runs scripts/db-reset.sql (TRUNCATE every public table except Liquibase history)
# so the collection / manual testing can start from a clean slate WITHOUT a full
# `docker compose down -v` + re-migrate + app restart. The app can stay up.
#
# Standalone use (feature_flag devs, against their own Postgres): just run it —
# it connects to the cross-repo contract DB (localhost:5432 feature_flag_db/ff_user)
# by default. Override with the standard PG* env vars (PGHOST/PGPORT/PGUSER/…).
#
# From the onward-dev-box workspace, prefer `make db-reset` there: it pipes this
# same SQL into the compose Postgres container, so it needs no host-side psql.
#
# Usage:
#   scripts/db-reset.sh
#   PGHOST=db.internal PGPORT=5433 scripts/db-reset.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SQL="$SCRIPT_DIR/db-reset.sql"

PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-5432}"
PGUSER="${PGUSER:-ff_user}"
PGDATABASE="${PGDATABASE:-feature_flag_db}"
export PGHOST PGPORT PGUSER PGDATABASE
export PGPASSWORD="${PGPASSWORD:-ff_password}"

[ -f "$SQL" ] || { echo "error: $SQL not found (run from the feature_flag checkout)" >&2; exit 1; }

command -v psql >/dev/null 2>&1 || {
  echo "error: psql not found on PATH" >&2
  echo "       install libpq (brew install libpq), or from onward-dev-box run: make db-reset" >&2
  exit 1
}

echo "db-reset: truncating public tables in $PGDATABASE on $PGHOST:$PGPORT (Liquibase history kept)…"
psql -v ON_ERROR_STOP=1 -q -f "$SQL"
echo "db-reset: done — business tables empty, migrations untouched."
