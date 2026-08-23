#!/usr/bin/env bash
# issue-board.sh — drive the GitHub Project (v2) board + issue assignee for the
# feature_flag issue workflow. Used by the `issue-workflow` skill so that
# starting/finishing an issue keeps the "Digital banking" board honest.
#
# Usage:
#   issue-board.sh start <issue#> [assignee]   # assign + move card to "In progress"
#   issue-board.sh ready <issue#>              # move card to "Ready For Testing"
#   issue-board.sh done  <issue#>              # move card to "Done"
#   issue-board.sh status <issue#>             # print the card's current status
#   issue-board.sh estimate <issue#> <SIZE> <hours>  # set Size (XS|S|M|L|XL) + Estimate
#
# `assignee` defaults to the authenticated gh user (whoever is driving), so the
# board reflects the actual developer rather than a hardcoded name.
#
# Requires: gh CLI authenticated WITH the `project` scope (read:project alone
# is not enough to mutate the board). If a mutation fails with a scope error,
# run:  gh auth refresh -s project
#
# NOTE: the "Digital banking" board is org-wide and hosts cards from MULTIPLE
# repos, so an issue number alone is NOT a unique key (e.g. feature_flag#4 and
# banking-knowledge-base#4 both exist). Every card lookup below therefore filters
# on `.content.repository == "$REPO"` as well as the number. Do not relax that or
# a command can silently move another repo's card. See issue #12.
set -euo pipefail

REPO="Aibles-Java/feature_flag"
PROJECT_OWNER="Aibles-Java"
PROJECT_NUMBER="3"           # "Digital banking"
STATUS_FIELD="Status"
SIZE_FIELD="Size"
ESTIMATE_FIELD="Estimate"

die() { echo "issue-board: $*" >&2; exit 1; }

cmd="${1:-}"
issue="${2:-}"
[ -n "$cmd" ]   || die "usage: issue-board.sh <start|ready|done|status|estimate> <issue#> [args]"
[ -n "$issue" ] || die "missing issue number"
command -v gh >/dev/null 2>&1 || die "gh CLI not found on PATH"

# --- board metadata (resolved at runtime; not hardcoded so it survives edits) --
project_id()      { gh project view "$PROJECT_NUMBER" --owner "$PROJECT_OWNER" --format json -q '.id'; }
status_field_id() { gh project field-list "$PROJECT_NUMBER" --owner "$PROJECT_OWNER" --format json \
                      -q ".fields[] | select(.name==\"$STATUS_FIELD\") | .id"; }
status_option_id() { # $1 = option name
  gh project field-list "$PROJECT_NUMBER" --owner "$PROJECT_OWNER" --format json \
    -q ".fields[] | select(.name==\"$STATUS_FIELD\") | .options[] | select(.name==\"$1\") | .id"; }

field_id() { # $1 = field name
  gh project field-list "$PROJECT_NUMBER" --owner "$PROJECT_OWNER" --format json \
    -q ".fields[] | select(.name==\"$1\") | .id"; }
option_id() { # $1 = field name, $2 = option name
  gh project field-list "$PROJECT_NUMBER" --owner "$PROJECT_OWNER" --format json \
    -q ".fields[] | select(.name==\"$1\") | .options[] | select(.name==\"$2\") | .id"; }

issue_url() { echo "https://github.com/$REPO/issues/$issue"; }

# Post a message to Slack when a webhook is configured. Opt-in and best-effort:
# if SLACK_WEBHOOK_URL is unset the call is a no-op, and a failed POST never
# fails the board command (the board move is the source of truth, not Slack).
slack_notify() { # $1 = message text
  [ -n "${SLACK_WEBHOOK_URL:-}" ] || return 0
  command -v curl >/dev/null 2>&1 || { echo "issue-board: curl not found, skipping Slack" >&2; return 0; }
  local text="$1" payload
  # Minimal, safe JSON: escape backslashes and double-quotes in the text.
  text=${text//\\/\\\\}
  text=${text//\"/\\\"}
  payload="{\"text\":\"$text\"}"
  curl -sf -X POST -H 'Content-Type: application/json' \
    --data "$payload" "$SLACK_WEBHOOK_URL" >/dev/null \
    || echo "issue-board: Slack notification failed (ignored)" >&2
}

# Return the board item id for this issue, adding it to the board if missing.
ensure_item() {
  local item
  item=$(gh project item-list "$PROJECT_NUMBER" --owner "$PROJECT_OWNER" --limit 200 --format json \
    -q ".items[] | select(.content.type==\"Issue\" and .content.number==$issue and .content.repository==\"$REPO\") | .id" 2>/dev/null | head -1)
  if [ -z "$item" ]; then
    gh project item-add "$PROJECT_NUMBER" --owner "$PROJECT_OWNER" --url "$(issue_url)" >/dev/null
    item=$(gh project item-list "$PROJECT_NUMBER" --owner "$PROJECT_OWNER" --limit 200 --format json \
      -q ".items[] | select(.content.type==\"Issue\" and .content.number==$issue and .content.repository==\"$REPO\") | .id" 2>/dev/null | head -1)
  fi
  [ -n "$item" ] || die "could not locate or create board item for issue #$issue"
  echo "$item"
}

set_status() { # $1 = option name
  local option="$1" item pid fid oid
  item=$(ensure_item)
  pid=$(project_id)
  fid=$(status_field_id)
  oid=$(status_option_id "$option")
  [ -n "$oid" ] || die "status option '$option' not found on the board"
  gh project item-edit --id "$item" --project-id "$pid" \
    --field-id "$fid" --single-select-option-id "$oid" >/dev/null
  echo "issue #$issue → status '$option'"
}

set_estimate() { # $1 = size option name, $2 = estimate in hours
  local size="$1" hours="$2" item pid sfid soid efid
  soid=$(option_id "$SIZE_FIELD" "$size")
  [ -n "$soid" ] || die "size '$size' not found on the board (expected XS|S|M|L|XL)"
  efid=$(field_id "$ESTIMATE_FIELD")
  [ -n "$efid" ] || die "field '$ESTIMATE_FIELD' not found on the board"
  item=$(ensure_item)
  pid=$(project_id)
  sfid=$(field_id "$SIZE_FIELD")
  gh project item-edit --id "$item" --project-id "$pid" \
    --field-id "$sfid" --single-select-option-id "$soid" >/dev/null \
    || die "failed to set Size for issue #$issue (no fields changed)"
  gh project item-edit --id "$item" --project-id "$pid" \
    --field-id "$efid" --number "$hours" >/dev/null \
    || die "Size '$size' was set but the Estimate write failed — card is inconsistent; re-run: issue-board.sh estimate $issue $size $hours"
  echo "issue #$issue → Size '$size', Estimate ${hours}h"
}

print_status() {
  gh project item-list "$PROJECT_NUMBER" --owner "$PROJECT_OWNER" --limit 200 --format json \
    -q ".items[] | select(.content.type==\"Issue\" and .content.number==$issue and .content.repository==\"$REPO\") | .status" 2>/dev/null
}

case "$cmd" in
  start)
    assignee="${3:-$(gh api user -q '.login')}"
    [ -n "$assignee" ] || die "could not resolve an assignee (gh api user failed)"
    gh issue edit "$issue" --repo "$REPO" --add-assignee "$assignee" >/dev/null
    echo "issue #$issue → assigned to @$assignee"
    set_status "In progress"
    ;;
  ready)
    set_status "Ready For Testing"
    slack_notify ":test_tube: Issue #$issue is *Ready For Testing* — $(issue_url)"
    ;;
  done)  set_status "Done" ;;
  status) print_status ;;
  estimate)
    size="${3:-}"; hours="${4:-}"
    [ -n "$size" ] && [ -n "$hours" ] || die "usage: issue-board.sh estimate <issue#> <SIZE> <hours>"
    [[ "$size" =~ ^(XS|S|M|L|XL)$ ]] || die "size must be one of XS|S|M|L|XL (got '$size')"
    [[ "$hours" =~ ^[0-9]+([.][0-9]+)?$ ]] || die "hours must be a number (got '$hours')"
    [[ "$hours" =~ ^0+([.]0+)?$ ]] && die "hours must be greater than 0"
    set_estimate "$size" "$hours"
    ;;
  *) die "unknown command '$cmd' (expected start|ready|done|status|estimate)" ;;
esac
