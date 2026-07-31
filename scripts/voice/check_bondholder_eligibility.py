#!/usr/bin/env python3
# recipe-callable: local-ok
"""Recipe-callable wrapper around BondholderVoiceEligibility (Java, #1028).

The pure-logic decision lives in
  core/src/main/java/.../recipe/BondholderVoiceEligibility.java
because the same check is consumed by both:
  1. This recipe step (cron-time eligibility gate)
  2. The notification path that surfaces the consent prompt to the
     bondholder when they first become eligible

This wrapper:
  - Gathers the seven Inputs from SQL (bonds, classifier_events, etc.).
  - Shells out to a small `wyrd recipe bondholder-eligibility` Java CLI
    to run the canonical decision (or, when that CLI is unavailable,
    emits a structured "tool_missing" deny so the recipe stops safely).
  - Emits the gate JSON.

Output (stdout, single JSON line):
    {"bondholder_eligible": 0|1,
     "eligibility_deny_reason": "<reason or null>",
     "eligibility_detail": "<string>",
     "corpus_pairs": N, "bond_age_days": N, "distinct_sessions": N,
     "bond_state": "<state>", "substrate_pressure_30d": <float>,
     "vector_age_days": N|null, "new_turns_since_last_fit": N}

For v0.1: if the Java CLI isn't on PATH (fresh install, no zone running),
emit `bondholder_eligible: 0` with a structured "cli_missing" reason so
the recipe stops cleanly instead of crashing.
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--bondholder", required=True,
                    help="Bondholder DID.")
    ap.add_argument("--agent", required=True,
                    help="Agent (companion) DID — the bonded familiar.")
    ap.add_argument("--min-corpus-pairs", type=int, default=30)
    ap.add_argument("--min-bond-age-days", type=int, default=14)
    ap.add_argument("--min-distinct-sessions", type=int, default=5)
    ap.add_argument("--required-bond-state", default="ACTIVE")
    ap.add_argument("--substrate-pressure-threshold", type=float, default=0.30)
    ap.add_argument("--min-new-turns", type=int, default=50)
    args = ap.parse_args()

    wyrd = shutil.which("wyrd")
    if not wyrd:
        # Try repo-local bin/wyrd.
        repo = Path(__file__).resolve().parents[2]
        cand = repo / "bin" / "wyrd"
        if cand.is_file():
            wyrd = str(cand)
    if not wyrd:
        print(json.dumps({
            "bondholder_eligible": 0,
            "eligibility_deny_reason": "cli_missing",
            "eligibility_detail": "wyrd CLI not on PATH — recipe stops",
            "bondholder_did": args.bondholder,
            "agent_did": args.agent,
        }))
        return

    try:
        result = subprocess.run([
            wyrd, "recipes", "bondholder-eligibility",
            "--bondholder", args.bondholder,
            "--agent", args.agent,
            "--min-corpus-pairs", str(args.min_corpus_pairs),
            "--min-bond-age-days", str(args.min_bond_age_days),
            "--min-distinct-sessions", str(args.min_distinct_sessions),
            "--required-bond-state", args.required_bond_state,
            "--substrate-pressure-threshold", str(args.substrate_pressure_threshold),
            "--min-new-turns", str(args.min_new_turns),
        ], capture_output=True, text=True, timeout=60)
    except (FileNotFoundError, subprocess.TimeoutExpired) as e:
        print(json.dumps({
            "bondholder_eligible": 0,
            "eligibility_deny_reason": "cli_invocation_failed",
            "eligibility_detail": str(e),
        }))
        return

    # Java CLI emits a single JSON line on stdout (last line).
    for line in reversed((result.stdout or "").splitlines()):
        line = line.strip()
        if not line:
            continue
        try:
            out = json.loads(line)
            print(json.dumps(out))
            return
        except json.JSONDecodeError:
            continue

    # No structured output → safe-deny.
    print(json.dumps({
        "bondholder_eligible": 0,
        "eligibility_deny_reason": "no_structured_output",
        "exit_code": result.returncode,
        "stderr_tail": (result.stderr or "")[-300:],
    }))


if __name__ == "__main__":
    main()
