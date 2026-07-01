---
name: save-memory
description: >
  Distill the current session into .claude/memory/: durable decisions and
  conventions, an updated HANDOFF.md for the next session, and a one-line
  entry in MEMORY.md. Trigger phrases: "save memory", "/save-memory".
metadata:
  template_version: "2.4.0"
---

# /save-memory

Distills this session's work into `.claude/memory/`, the repo-local memory store
(see `.claude/memory/README.md` for how the system works).

## What to do

1. **Review the session.** Identify what actually happened: decisions made (and why),
   conventions or gotchas discovered that aren't already in `CLAUDE.md`, and work left
   in progress.

2. **Durable facts → `decisions/` or `conventions/`.**
   - A one-off architectural or design decision with real rationale → new file
     `decisions/NNNN-slug.md` (increment `NNNN`, zero-padded 4 digits). Include: what was
     decided, why, and alternatives considered if relevant.
   - A recurring convention or gotcha not yet documented in `CLAUDE.md` → new or updated
     file `conventions/slug.md`.
   - Skip this step entirely if nothing durable came out of the session — not every
     session produces a decision worth keeping.

3. **Update `MEMORY.md`.** Add one line per new file created in step 2:
   `- [Title](decisions/NNNN-slug.md) — one-line hook`. Keep the whole index scannable;
   don't restate the full content here.

4. **Overwrite `HANDOFF.md`.** Replace its contents with:
   - **Current WIP** — what's in progress right now, concretely (files touched, what's
     left).
   - **Context to Load** — the specific `decisions/`/`conventions/` files (if any) the next
     session should read first. Keep this list short.
   - **Next steps** — the immediate next action(s).

5. **Append to `sessions/YYYY-MM-DD.md`.** A short (3-6 line) summary of what happened this
   session. If the file already exists for today (multiple sessions same day), append a new
   entry rather than overwriting.

## Write rules

- Use today's actual date (ask the system/environment for it — do not guess).
- No generic placeholder text — every entry should reflect what actually happened.
- If nothing worth saving happened this session (pure Q&A, no decisions or progress),
  say so and skip writing new decision/convention files — but still safe to leave
  `HANDOFF.md`/`MEMORY.md` unchanged in that case.
- Never overwrite existing `decisions/` or `conventions/` entries — they're append-only;
  create a new file instead if a past decision is superseded, and note the supersession
  in the new file.
