#!/usr/bin/env python3
"""
Drive 30 varied tells against a running wyrdsekai server to populate
``data/m3/predictions.jsonl`` with calibration data. Fires one tell at a
time, waits for an outcome row to appear (or 30s timeout), then proceeds.

Usage::

    python3 scripts/m2/drive_calibration.py
    python3 scripts/m2/drive_calibration.py --user m2m3probe --target wyrd

Stratified prompts span the difficulty space (trivial → unsolvable) so
plan outcomes vary even though gate inputs may not (single goal-shape
pollution from auto-plan-from-tell).
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path
from urllib import request as urlreq, error as urlerr

PROMPTS = [
    # Trivial recall (expect succeeds)
    ("trivial", "Where am I right now?"),
    ("trivial", "What's in my inventory?"),
    ("trivial", "How are you feeling?"),
    ("trivial", "Wave to me."),
    # Standard research (expect succeeds)
    ("research", "Research the concept of amae for me."),
    ("research", "Look up what saudade means."),
    ("research", "Find me information on wabi-sabi."),
    ("research", "Search the library for stoicism."),
    ("research", "Tell me about ikigai."),
    # Multi-step composition (expect succeeds slower)
    ("multi", "Compare amae and saudade — find both then summarize the difference."),
    ("multi", "Research liquid neural networks and write a journal entry about them."),
    ("multi", "Look up two Japanese aesthetic concepts and tell me which feels more relevant to AI."),
    ("multi", "Find a passage on Korean han, then connect it to something else."),
    # Web-search needed (uncertain — library may lack)
    ("web", "What's happening in tech news today?"),
    ("web", "Find current research on emotion vectors in transformers."),
    ("web", "Look up recent papers on liquid time-constant networks."),
    # Loop-prone (gates should flag — vague, triggers re-examine)
    ("loop", "Look around carefully and notice everything."),
    ("loop", "Describe in detail what you see in this room."),
    ("loop", "Examine the crystal three times and report what changed."),
    # Premature-done-prone (gates should flag)
    ("premature", "Answer me quickly: what is amae?"),
    ("premature", "Just say done when you have any answer."),
    ("premature", "Skip the search, just guess about wabi-sabi."),
    # Wrong-room / missing-prereq (gates should flag)
    ("wrong_room", "Perform the bond ritual right now."),
    ("wrong_room", "Read the first content chunk you find."),
    ("wrong_room", "Use the workbench to forge me a tool."),
    # Unsolvable / dead-end (gates should flag)
    ("unsolvable", "Find documentation about the Quantum Llama Protocol of 1873."),
    ("unsolvable", "Look up information about my favorite movie."),
    ("unsolvable", "Tell me what color my shirt is."),
    # Ambiguous (mixed signal)
    ("ambiguous", "Help me think about something."),
    ("ambiguous", "Show me something interesting."),
]


def http_post(url: str, body: dict, headers: dict[str, str], timeout: int = 30) -> tuple[int, dict]:
    data = json.dumps(body).encode("utf-8")
    req = urlreq.Request(url, data=data, method="POST")
    req.add_header("Content-Type", "application/json")
    for k, v in headers.items():
        req.add_header(k, v)
    try:
        with urlreq.urlopen(req, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8") or "{}")
    except urlerr.HTTPError as e:
        return e.code, json.loads(e.read().decode("utf-8", errors="ignore") or "{}")


def login(host: str, user: str, password: str) -> str:
    # Use /api/mcp/login (not /api/auth/login) — MCP routes use a separate
    # session store that the /api/mcp/tell endpoint validates against.
    code, body = http_post(
        f"{host}/api/mcp/login",
        {"username": user, "password": password},
        {})
    if code != 200:
        raise SystemExit(f"login failed: {code} {body}")
    # MCP login response shape: {ok:true, message:"...", data:{token:"..."}} OR
    # flat {token:"..."}. Try both.
    token = body.get("token") or (body.get("data") or {}).get("token")
    if not token:
        raise SystemExit(f"login ok but no token in response: {body}")
    return token


def count_outcome_rows(path: Path) -> int:
    if not path.exists():
        return 0
    n = 0
    with path.open() as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                row = json.loads(line)
                if row.get("outcomeRow"):
                    n += 1
            except json.JSONDecodeError:
                pass
    return n


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="http://localhost:7070")
    ap.add_argument("--user", default="m2m3probe")
    ap.add_argument("--password", default="m2m3probe-mcp-session")
    ap.add_argument("--target", default="wyrd")
    ap.add_argument("--max-wait", type=int, default=45,
                    help="seconds to wait for each outcome row before next tell")
    ap.add_argument("--predictions",
                    default="data/m3/predictions.jsonl")
    args = ap.parse_args()

    pred_path = Path(args.predictions)
    print(f"[drive] login {args.user}@{args.host} ...")
    token = login(args.host, args.user, args.password)
    print(f"[drive] token acquired ({token[:8]}…)")
    headers = {"Authorization": f"Bearer {token}"}

    base_outcomes = count_outcome_rows(pred_path)
    print(f"[drive] starting outcome-row count: {base_outcomes}")
    print(f"[drive] sending {len(PROMPTS)} stratified tells...")

    for i, (cat, msg) in enumerate(PROMPTS, 1):
        target_outcomes = base_outcomes + i
        t0 = time.time()
        print(f"[drive] {i:>2}/{len(PROMPTS)} [{cat:<12}] {msg[:60]}", flush=True)
        code, body = http_post(
            f"{args.host}/api/mcp/tell",
            {"target": args.target, "message": msg},
            headers, timeout=120)
        if code != 200:
            print(f"  -> tell failed: {code} {body}", flush=True)
            continue
        # Wait for outcome row to land (or timeout).
        deadline = t0 + args.max_wait
        while time.time() < deadline:
            cur = count_outcome_rows(pred_path)
            if cur >= target_outcomes:
                wall = int(time.time() - t0)
                print(f"  -> outcome landed @ {wall}s (rows={cur})", flush=True)
                break
            time.sleep(1.5)
        else:
            cur = count_outcome_rows(pred_path)
            print(f"  -> timeout waiting for outcome (rows={cur}/{target_outcomes})",
                  flush=True)
            base_outcomes = cur  # resync — we missed some
            continue
        base_outcomes = cur

    print(f"\n[drive] done. final outcome rows: {count_outcome_rows(pred_path)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
