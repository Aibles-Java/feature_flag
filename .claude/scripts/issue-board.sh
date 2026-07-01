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
#
# `assignee` defaults to the authenticated gh user (whoever is driving), so the
# board reflects the actual developer rather than a hardcoded name.
#
# Requires: gh CLI authenticated WITH the `project` scope (read:project alone
# is not enough to mutate the board). If a mutation fails with a scope error,
# run:  gh auth refresh -s project
set -euo pipefail

REPO="Aibles-Java/feature_flag"
PROJECT_OWNER="Aibles-Java"
PROJECT_NUMBER="3"           # "Digital banking"
STATUS_FIELD="Status"

die() { echo "issue-board: $*" >&2; exit 1; }

cmd="${1:-}"
issue="${2:-}"
[ -n "$cmd" ]   || die "usage: issue-board.sh <start|ready|done|status> <issue#> [assignee]"
[ -n "$issue" ] || die "missing issue number"
command -v gh >/dev/null 2>&1 || die "gh CLI not found on PATH"

# --- board metadata (resolved at runtime; not hardcoded so it survives edits) --
project_id()      { gh project view "$PROJECT_NUMBER" --owner "$PROJECT_OWNER" --format json -q '.id'; }
status_field_id() { gh project field-list "$PROJECT_NUMBER" --owner "$PROJECT_OWNER" --format json \
                      -q ".fields[] | select(.name==\"$STATUS_FIELD\") | .id"; }
status_option_id() { # $1 = option name
  gh project field-list "$PROJECT_NUMBER" --owner "$PROJECT_OWNER" --format json \
    -q ".fields[] | select(.name==\"$STATUS_FIELD\") | .options[] | select(.name==\"$1\") | .id"; }

issue_url() { echo "https://github.com/$REPO/issues/$issue"; }

# Return the board item id for this issue, adding it to the board if missing.
ensure_item() {
  local item
  item=$(gh project item-list "$PROJECT_NUMBER" --owner "$PROJECT_OWNER" --format json \
    -q ".items[] | select(.content.type==\"Issue\" and .content.number==$issue) | .id" 2>/dev/null | head -1)
  if [ -z "$item" ]; then
    gh project item-add "$PROJECT_NUMBER" --owner "$PROJECT_OWNER" --url "$(issue_url)" >/dev/null
    item=$(gh project item-list "$PROJECT_NUMBER" --owner "$PROJECT_OWNER" --format json \
      -q ".items[] | select(.content.type==\"Issue\" and .content.number==$issue) | .id" 2>/dev/null | head -1)
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

print_status() {
  gh project item-list "$PROJECT_NUMBER" --owner "$PROJECT_OWNER" --format json \
    -q ".items[] | select(.content.type==\"Issue\" and .content.number==$issue) | .status" 2>/dev/null
}

case "$cmd" in
  start)
    assignee="${3:-$(gh api user -q '.login')}"
    [ -n "$assignee" ] || die "could not resolve an assignee (gh api user failed)"
    gh issue edit "$issue" --repo "$REPO" --add-assignee "$assignee" >/dev/null
    echo "issue #$issue → assigned to @$assignee"
    set_status "In progress"
    ;;
  ready) set_status "Ready For Testing" ;;
  done)  set_status "Done" ;;
  status) print_status ;;
  *) die "unknown command '$cmd' (expected start|ready|done|status)" ;;
esac
