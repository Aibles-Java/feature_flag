# The "Digital banking" board is multi-repo — always scope by repository

The GitHub Project (v2) board **"Digital banking" (project #3, `Aibles-Java` org)** is
org-wide and holds cards from **several repositories at once**. An issue *number* is
therefore **not** a unique key on the board: `feature_flag#4` and
`banking-knowledge-base#4` both exist as separate cards.

## The bug this caused

`.claude/scripts/issue-board.sh` originally located a card with number only:

```sh
select(.content.type=="Issue" and .content.number==$issue)   # then | head -1
```

Running `issue-board.sh ready 4` for `feature_flag#4` matched `banking-knowledge-base#4`
first and moved **that** card to "Ready For Testing", while our card stayed at
"In progress". Non-deterministic: `item-list` ordering decided which card got hit, so
`start` and `ready` in the same session moved *different* cards. Tracked + fixed in
issue #12.

## The rule

Any board lookup — script or ad-hoc `gh project item-list` — MUST filter on repository:

```sh
select(.content.type=="Issue" and .content.number==$issue \
       and .content.repository=="Aibles-Java/feature_flag")
```

Also pass `--limit 200` to `gh project item-list`: it defaults to **30** items, and this
board has more, so a target card can be missed entirely (→ spurious add / silent no-op).

When mutating a card by explicit item id, first confirm exactly one card matches
`(number, repo)` — fail loud on 0 or >1 rather than `head -1`.

See [[0005-issue-workflow-board-and-memory-gate.md]] for the board script's original design.
