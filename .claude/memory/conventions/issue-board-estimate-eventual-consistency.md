# issue-board.sh estimate — first run can fail on eventual consistency

**Seen:** 2026-07-11, filing #48/#49/#50.

`issue-board.sh estimate <n> <SIZE> <hours>` (and any command via `ensure_item`) died with
`could not locate or create board item for issue #<n>` on the **first** run for a
brand-new issue — for all three at once.

**Cause:** `ensure_item()` calls `gh project item-add`, then **immediately** re-runs
`gh project item-list` to read the new item id. The GitHub Projects API is
eventually-consistent: the item-add succeeds but the item is not yet visible to the very
next list call, so the id lookup returns empty and the script `die`s. The card *is*
actually added.

**Fix / workaround:** just **re-run the same command** — on the second run the card is
present and `ensure_item` finds it, so Size/Estimate write cleanly. No data lost; the
failed first run is not a real error.

**If hardening the script later:** add a short retry/sleep between `item-add` and the
follow-up `item-list` in `ensure_item`, or read the id from the `item-add --format json`
output directly instead of re-listing. See [[shared-board-repo-scoping]] for the related
multi-repo card-matching rule in the same function.
