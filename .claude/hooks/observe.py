"""
observe.py — Claude Code tool call logger for shipwithai-starter.
Copy to .claude/hooks/observe.py in the user's project.

PostToolUse hook for Edit, Write, Bash.
Writes one JSONL line per call to .claude/logs/YYYY-MM-DD.jsonl.
Silent-fails — never blocks tool execution.
"""
from __future__ import annotations

import datetime
import json
import os
import pathlib
import sys

MAX_LOG_BYTES = 50 * 1024 * 1024  # 50 MB

# Anchor log directory to .claude/logs/ relative to this hook's installed location.
# observe.py lives at .claude/hooks/observe.py, so parent.parent = .claude/
_HOOK_FILE = pathlib.Path(__file__).resolve()
_LOG_DIR = _HOOK_FILE.parent.parent / 'logs'
# Project root = directory containing .claude/ (i.e., three levels up from this file)
_PROJECT_ROOT = _HOOK_FILE.parent.parent.parent


def build_event(payload: dict, _project_root: pathlib.Path | None = None) -> dict:
    """Build a log event dict from the hook payload. Never logs content."""
    tool = payload.get('tool_name', '')
    tool_input = payload.get('tool_input', {})
    session_id = os.environ.get('CLAUDE_SESSION_ID', '')
    project_root = _project_root if _project_root is not None else _PROJECT_ROOT

    event = {
        'ts': datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'),
        'sid': session_id[:8],
        'event': 'tool',
        'tool': tool,
    }

    if tool in ('Edit', 'Write'):
        raw_path = tool_input.get('file_path', '')
        try:
            resolved = str(pathlib.Path(raw_path).resolve()) if raw_path else ''
            event['file'] = os.path.relpath(resolved, project_root) if resolved else ''
        except (ValueError, OSError):
            event['file'] = raw_path

    elif tool == 'Bash':
        cmd = tool_input.get('command', '')
        parts = cmd.split()
        event['cmd'] = parts[0] if parts else ''

    return event


def write_log(event: dict) -> None:
    """Append event to today's JSONL log file."""
    _LOG_DIR.mkdir(parents=True, exist_ok=True)

    total = sum(f.stat().st_size for f in _LOG_DIR.glob('*.jsonl'))
    if total > MAX_LOG_BYTES:
        return

    today = datetime.date.today().isoformat()
    with open(_LOG_DIR / f'{today}.jsonl', 'a', encoding='utf-8') as f:
        f.write(json.dumps(event, separators=(',', ':')) + '\n')


def main(stdin_bytes: bytes | None = None) -> None:
    """Main entry point. stdin_bytes is injectable for testing."""
    raw = stdin_bytes if stdin_bytes is not None else sys.stdin.buffer.read()

    # Always passthrough stdin so downstream hooks in the chain receive data
    sys.stdout.buffer.write(raw)

    if os.environ.get('DISABLE_OBSERVE'):
        return

    try:
        payload = json.loads(raw) if raw and raw.strip() else {}
    except Exception:
        payload = {}

    if not payload.get('tool_name'):
        return

    try:
        event = build_event(payload)
        write_log(event)
    except Exception:
        pass  # Silent fail — never block tool execution


if __name__ == '__main__':
    try:
        main()
    except Exception:
        pass  # Silent fail — never block tool execution
