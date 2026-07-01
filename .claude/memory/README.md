# Project Memory — feature_flag

*Installed by /shipwithai-starter (Tier 3) on 2026-07-01. Template version: 2.4.0.*

This is a **repo-local, version-controlled** memory system for Claude Code sessions working
on this project. It is separate from any personal/global memory a Claude Code instance may
keep outside the repo — this one lives in git, so every teammate (and every future session)
shares the same context.

## How it works

1. **Session start** — the `load-memory.sh` hook (SessionStart) prints `MEMORY.md` and
   `HANDOFF.md` into context automatically. Claude should read only the specific files listed
   under "Context to Load" in `HANDOFF.md` (lazy load — don't eagerly read every decision).
2. **During work** — Claude works normally, referencing loaded memory as needed.
3. **Session end** — the `remind-save.sh` hook (Stop) nudges Claude to run `/save-memory` if
   real work happened this session and it hasn't been saved yet.
4. **`/save-memory`** — distills the session into the store below: durable facts go to
   `decisions/` or `conventions/`, ephemeral WIP goes to `HANDOFF.md`, and a short summary is
   appended to `sessions/`.

## Store layout

| Path | Lifetime | Holds |
|---|---|---|
| `MEMORY.md` | updated continuously | one-line index of every memory below |
| `decisions/NNNN-slug.md` | durable, append-only | a decision + its rationale |
| `conventions/slug.md` | durable | a convention or gotcha not already in CLAUDE.md |
| `HANDOFF.md` | ephemeral, overwritten each save | current WIP + "Context to Load" for next session |
| `sessions/YYYY-MM-DD.md` | append per day | a short session summary |

## Opting out

To disable the Stop nudge, remove the `remind-save.sh` entry from `.claude/settings.json`'s
`hooks.Stop` array. To disable session-start loading, remove the `load-memory.sh` entry from
`hooks.SessionStart`. The store itself is harmless to leave in place either way.

## Relationship to CLAUDE.md

`CLAUDE.md` is the stable, curated project reference (architecture, conventions, workflow
gates). This memory system is the *working* layer on top of it — day-to-day decisions and
handoff notes that haven't been promoted into `CLAUDE.md` yet. Periodically fold durable
`decisions/`/`conventions/` entries into `CLAUDE.md` or `docs/adr/` if they become permanent.
