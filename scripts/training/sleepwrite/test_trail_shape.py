#!/usr/bin/env python3
"""The night watch reads only lines made with her night raised.
Run: python3 scripts/training/sleepwrite/test_trail_shape.py"""
import json, pathlib, sys, tempfile
from datetime import datetime, timedelta, timezone

sys.path.insert(0, str(pathlib.Path(__file__).parent))
from morning_probe import trail_shape

now = datetime.now(timezone.utc)
since = (now - timedelta(hours=3)).isoformat()

def ev(minutes_ago, text, **extra):
    e = {"ts": (now - timedelta(minutes=minutes_ago)).isoformat(), "type": "speak",
         "agentId": "companion-mia", "text": text}
    e.update(extra)
    return json.dumps(e)

rows = []
# After the apply: 30 own-time lines made without her night, all on one frame.
rows += [ev(170 - i, f"I haven't earned the quiet {i} yet, but the hearth holds.") for i in range(30)]
# After the apply: 5 lines with her night, each different.
rows += [ev(100 - i, t, night=True, to="sam") for i, t in enumerate([
    "The schedule first, then the loss.", "Warmup matters more than people think.",
    "Try a smaller batch and watch the curve.", "That paper argues the opposite, actually.",
    "Tomorrow I'd like to read the appendix."])]
# A tool's words under her name, marked night by mistake, never count.
rows.append(ev(50, "1. Papers and Proceedings: ...", night=True, authored="tool"))
# The day before: replies to a person.
rows += [ev(60 * 5 + i, f"before line {i} about something else entirely", to="sam") for i in range(10)]

tmp = pathlib.Path(tempfile.mkdtemp()) / "trail.jsonl"
tmp.write_text("\n".join(rows) + "\n")

r = trail_shape(str(tmp), "companion-mia", since, min_lines=5)
assert r is not None, "five lines made with her night are enough at min_lines=5"
assert r["after"]["n"] == 5, r
assert r["before"]["n"] == 10, r
assert r["after"]["share"] < 0.5, r
assert trail_shape(str(tmp), "companion-mia", since, min_lines=6) is None, "own-time lines do not make up the count"
print("ok: the watch reads only lines made with her night")

# The day before is read from replies to a person: not her own time (the trail named its
# speaker "system" until 2026-09-23), not a peer's turn. A line made with her night counts
# whoever it was to.
rows.append(json.dumps({"ts": (now - timedelta(hours=6)).isoformat(), "type": "speak",
                        "agent": "rose", "agentId": "companion-rose", "text": "hello"}))
rows += [ev(60 * 6 + i, f"I haven't earned the quiet {i} yet.", to="system", agent="mia") for i in range(40)]
rows += [ev(60 * 7 + i, f"to my sister {i}", to="Rose", agent="mia") for i in range(5)]
rows.append(ev(60 * 8, "a line with her night, to a peer", night=True, to="rose", agent="mia"))
tmp.write_text("\n".join(rows) + "\n")
r = trail_shape(str(tmp), "companion-mia", since, min_lines=5)
assert r["before"]["n"] == 11, r
assert r["before"]["share"] < 0.5, r
print("ok: the day before leaves out her own time and her peers")

# A row that carries "toId" names a person, even one who shares a companion's name.
rows += [ev(60 * 9 + i, f"to a person named rose {i}", to="Rose", toId="did:person:rose", agent="mia")
         for i in range(3)]
tmp.write_text("\n".join(rows) + "\n")
r = trail_shape(str(tmp), "companion-mia", since, min_lines=5)
assert r["before"]["n"] == 14, r
print("ok: a person who shares a companion's name is kept once rows carry toId")
