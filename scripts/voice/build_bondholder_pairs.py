#!/usr/bin/env python3
# recipe-callable: local-ok
"""Build contrast pairs from a bondholder's recent conversation history.

Companion to align-bondholder-voice.recipe.yaml. Mines the bondholder's
last ~N turns from conversation history, pairs each turn (the bondholder's
preferred conversational register) with a generic baseline ("how Wyrd
would have replied without personalization"). Writes pair JSONL for the
extract-steering-vector step to consume.

The "generic baseline" side of each pair comes from the household-wide
voice vector's expected response to the same prompt — same prompt fed
through the bondholder's preferred direction vs the baseline direction.
This is what makes the resulting steering vector specifically *about*
this bondholder's register, not about random style variation.

For OSS v0.1: emits a structured "not implemented" output if the
required corpus source isn't ready. The recipe gate stops the run
safely (extract step sees zero pairs and aborts).

Output (stdout, single JSON line):
    {"bondholder_did": "<did>", "pairs_written": N, "pairs_path": "<path>"}
"""
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
from pathlib import Path


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--bondholder", required=True)
    ap.add_argument("--agent", required=True)
    ap.add_argument("--output", type=Path, default=None,
                    help="Output path (default /tmp/<bondholder>-pairs.jsonl)")
    ap.add_argument("--max-pairs", type=int, default=200,
                    help="Hard cap to avoid runaway corpus mining.")
    args = ap.parse_args()

    out = args.output or Path(f"/tmp/{args.bondholder}-pairs.jsonl")
    out.parent.mkdir(parents=True, exist_ok=True)

    # Delegate to a small `wyrd recipe bondholder-pairs` Java CLI subcommand
    # that knows how to read conversation history + apply the pair-mining
    # heuristic. Falls back to a structured "not implemented" output when
    # the CLI isn't reachable.
    wyrd = shutil.which("wyrd")
    if not wyrd:
        repo = Path(__file__).resolve().parents[2]
        cand = repo / "bin" / "wyrd"
        if cand.is_file():
            wyrd = str(cand)

    if not wyrd:
        out.write_text("")  # empty pair file → extract step sees zero
        print(json.dumps({
            "bondholder_did": args.bondholder,
            "pairs_written": 0,
            "pairs_path": str(out),
            "error": "cli_missing",
        }))
        return

    try:
        result = subprocess.run([
            wyrd, "recipes", "bondholder-pairs",
            "--bondholder", args.bondholder,
            "--agent", args.agent,
            "--output", str(out),
            "--max-pairs", str(args.max_pairs),
        ], capture_output=True, text=True, timeout=300)
    except (FileNotFoundError, subprocess.TimeoutExpired) as e:
        out.write_text("")
        print(json.dumps({
            "bondholder_did": args.bondholder, "pairs_written": 0,
            "pairs_path": str(out),
            "error": "cli_invocation_failed", "error_detail": str(e),
        }))
        return

    # Parse last JSON line from CLI output.
    for line in reversed((result.stdout or "").splitlines()):
        line = line.strip()
        if not line:
            continue
        try:
            payload = json.loads(line)
            payload.setdefault("pairs_path", str(out))
            print(json.dumps(payload))
            return
        except json.JSONDecodeError:
            continue

    # Fallback: count lines in output, emit.
    n = sum(1 for _ in out.open()) if out.exists() else 0
    print(json.dumps({
        "bondholder_did": args.bondholder,
        "pairs_written": n,
        "pairs_path": str(out),
        "exit_code": result.returncode,
        "stderr_tail": (result.stderr or "")[-300:],
    }))


if __name__ == "__main__":
    main()
