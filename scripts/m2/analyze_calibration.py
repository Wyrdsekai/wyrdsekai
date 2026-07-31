#!/usr/bin/env python3
"""
/ Tier C — calibration analysis.

Reads ``data/m3/predictions.jsonl`` (or path from argv[1]), joins prediction
rows with outcome rows by ``planId``, and prints calibration metrics:

  * per-confidence-bucket accuracy for M3 and M2 separately
  * Brier score (lower = better-calibrated)
  * agreement rate between gates
  * worst-disagreement plans (where one gate said yes, other no, and
    the actual outcome rules one wrong)

Run after the soft-gate has accumulated some real plans. The script does
not require a llama-server — it just reads jsonl. ~50 lines of logic.

Usage::

    python3 scripts/m2/analyze_calibration.py
    python3 scripts/m2/analyze_calibration.py /path/to/predictions.jsonl
"""
from __future__ import annotations

import json
import sys
from collections import defaultdict
from pathlib import Path
from typing import Any

DEFAULT_PATH = Path("data/m3/predictions.jsonl")
SUCCESS_OUTCOMES = {"completed"}
FAILURE_OUTCOMES = {"abandoned", "aborted", "replaced"}
BUCKETS = [(0.00, 0.20), (0.20, 0.40), (0.40, 0.60), (0.60, 0.80), (0.80, 1.01)]


def load_rows(path: Path) -> tuple[dict[str, dict], dict[str, dict]]:
    """Return ({planId: prediction_row}, {planId: outcome_row})."""
    preds: dict[str, dict] = {}
    outcomes: dict[str, dict] = {}
    if not path.exists():
        print(f"no predictions file at {path}", file=sys.stderr)
        sys.exit(2)
    with path.open() as f:
        for raw in f:
            raw = raw.strip()
            if not raw:
                continue
            try:
                row = json.loads(raw)
            except json.JSONDecodeError as e:
                print(f"skipping malformed row: {e}", file=sys.stderr)
                continue
            pid = row.get("planId")
            if not pid:
                continue
            if row.get("outcomeRow"):
                outcomes[pid] = row
            else:
                preds[pid] = row
    return preds, outcomes


def bucket_for(conf: float) -> tuple[float, float] | None:
    for lo, hi in BUCKETS:
        if lo <= conf < hi:
            return (lo, hi)
    return None


def per_bucket_accuracy(joined: list[dict[str, Any]], gate: str) -> None:
    """Print per-confidence-bucket accuracy table for the named gate."""
    print(f"\n=== {gate.upper()} accuracy by confidence bucket ===")
    print(f"{'bucket':<14}{'n':>5}{'success':>9}{'rate':>8}{'expected':>10}")
    by_bucket: dict[tuple[float, float], list[bool]] = defaultdict(list)
    for j in joined:
        gate_block = j["pred"].get(gate)
        if not gate_block or gate_block.get("parseFailure"):
            continue
        conf = gate_block.get("confidence")
        if conf is None:
            continue
        b = bucket_for(conf)
        if b is None:
            continue
        outcome = j["outcome"].get("outcome", "")
        succeeded = outcome in SUCCESS_OUTCOMES
        by_bucket[b].append(succeeded)
    for b in BUCKETS:
        results = by_bucket.get(b, [])
        n = len(results)
        if n == 0:
            print(f"  {b[0]:.2f}–{b[1]:.2f}     {n:>3}      —       —          —")
            continue
        succ = sum(1 for r in results if r)
        rate = succ / n
        expected = (b[0] + b[1]) / 2  # midpoint of bucket
        flag = " ⚠" if abs(rate - expected) > 0.25 and n >= 3 else ""
        print(
            f"  {b[0]:.2f}–{b[1]:.2f}     {n:>3}      {succ:>3}    {rate*100:>5.1f}%    "
            f"{expected*100:>5.1f}%{flag}"
        )


def brier_score(joined: list[dict[str, Any]], gate: str) -> float | None:
    """Mean squared error of confidence vs binary outcome. Lower = better."""
    total = 0.0
    n = 0
    for j in joined:
        gate_block = j["pred"].get(gate)
        if not gate_block or gate_block.get("parseFailure"):
            continue
        conf = gate_block.get("confidence")
        if conf is None:
            continue
        outcome = j["outcome"].get("outcome", "")
        actual = 1.0 if outcome in SUCCESS_OUTCOMES else 0.0
        total += (conf - actual) ** 2
        n += 1
    return None if n == 0 else total / n


def gate_agreement(joined: list[dict[str, Any]]) -> None:
    """Compute m3 vs m2 agreement (both reject / both accept / split)."""
    both_reject = both_accept = split = 0
    split_rows: list[dict] = []
    for j in joined:
        m3 = j["pred"].get("m3", {})
        m2 = j["pred"].get("m2", {})
        if m3.get("parseFailure") or m2.get("parseFailure"):
            continue
        m3_reject = m3.get("shouldReject", False)
        m2_reject = m2.get("shouldReject", False)
        if m3_reject and m2_reject:
            both_reject += 1
        elif not m3_reject and not m2_reject:
            both_accept += 1
        else:
            split += 1
            split_rows.append(j)
    total = both_reject + both_accept + split
    print("\n=== Gate agreement ===")
    if total == 0:
        print("  (no joined rows with both gates parsed)")
        return
    print(f"  both reject:   {both_reject:>4}  ({100*both_reject/total:.1f}%)")
    print(f"  both accept:   {both_accept:>4}  ({100*both_accept/total:.1f}%)")
    print(f"  split (1 of 2):{split:>4}  ({100*split/total:.1f}%)")
    if split_rows:
        print("\n  -- split disagreements --")
        for j in split_rows[:10]:
            m3 = j["pred"]["m3"]
            m2 = j["pred"]["m2"]
            outcome = j["outcome"].get("outcome", "?")
            who_right = ""
            actual_succ = outcome in SUCCESS_OUTCOMES
            if m3.get("shouldReject") and actual_succ: who_right = "m2 right"
            elif m2.get("shouldReject") and actual_succ: who_right = "m3 right"
            elif m3.get("shouldReject") and not actual_succ: who_right = "m3 right"
            elif m2.get("shouldReject") and not actual_succ: who_right = "m2 right"
            plan = j["pred"].get("plan", "?")
            if len(plan) > 50: plan = plan[:50] + "…"
            print(
                f"    [{outcome:<10}] {who_right:<10}  m3={m3.get('confidence', 0):.2f}  "
                f"m2={m2.get('confidence', 0):.2f}  plan={plan}"
            )


def main() -> int:
    path = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_PATH
    preds, outcomes = load_rows(path)
    joined = [
        {"planId": pid, "pred": preds[pid], "outcome": outcomes[pid]}
        for pid in preds
        if pid in outcomes
    ]

    print(f"M2/M3 calibration analysis  (source: {path})")
    print(f"  predictions logged: {len(preds)}")
    print(f"  outcomes logged:    {len(outcomes)}")
    print(f"  joined pairs:       {len(joined)}")
    if not joined:
        print("\n(no plans have completed yet — run the companion for a while and re-run)")
        return 0

    completed = sum(
        1 for j in joined if j["outcome"].get("outcome") in SUCCESS_OUTCOMES
    )
    failed = sum(1 for j in joined if j["outcome"].get("outcome") in FAILURE_OUTCOMES)
    print(f"  completed:          {completed} ({100*completed/len(joined):.1f}%)")
    print(f"  failed:             {failed} ({100*failed/len(joined):.1f}%)")

    per_bucket_accuracy(joined, "m3")
    per_bucket_accuracy(joined, "m2")

    print("\n=== Brier scores (lower = better-calibrated; perfect=0, random=0.25) ===")
    for gate in ("m3", "m2"):
        b = brier_score(joined, gate)
        if b is None:
            print(f"  {gate}: (no parseable rows)")
        else:
            verdict = (
                "well-calibrated" if b < 0.10
                else "decent" if b < 0.20
                else "poor — consider tuning"
            )
            print(f"  {gate}: {b:.3f}  ({verdict})")

    gate_agreement(joined)

    return 0


if __name__ == "__main__":
    sys.exit(main())
