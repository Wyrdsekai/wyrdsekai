#!/usr/bin/env python3
"""Build vitality_v2 SSD training corpus — balanced base + vitality.

Combines:
  - balanced_train.jsonl + balanced_valid.jsonl (drives + tools + soul + energy)
    with system prompts rewritten to vitality SYSTEM_BASE (full 14-tank vocab)
  - vitality_train.jsonl + vitality_valid.jsonl (cultural tanks + Tier 1
    anti-pathology + new drives — already uses vitality SYSTEM_BASE)

Output:
  - data/training/vitality_v2_train.jsonl
  - data/training/vitality_v2_valid.jsonl

Usage on gpu-host:
    python3 scripts/training/prepare_vitality_v2_corpus.py
"""

from __future__ import annotations

import argparse
import json
import random
import sys
from pathlib import Path

# Sibling import for vitality SYSTEM_BASE (the one that lists all 14 tanks)
sys.path.insert(0, str(Path(__file__).parent / "vitality"))
from generate_vitality_corpus import SYSTEM_BASE as VITALITY_SYSTEM_BASE  # noqa

random.seed(42)


def load_jsonl(path: Path) -> list[dict]:
    if not path.exists():
        return []
    with open(path) as f:
        return [json.loads(line) for line in f if line.strip()]


def rewrite_system_prompt_to_vitality(ex: dict) -> dict:
    """For balanced (non-vitality) turns: replace system prompt with vitality
    SYSTEM_BASE while preserving any per-turn drive prefix (the [drives: ...]
    block appended after \\n\\n).

    Balanced turns come from drive/tool/soul/energy generators that use their
    own SYSTEM_BASE. We swap the header so the trained model sees consistent
    vocabulary across all turns. The per-turn prefix (if present) is preserved
    so the drive context still drives the response.
    """
    msgs = list(ex["messages"])
    for i, msg in enumerate(msgs):
        if msg["role"] != "system":
            continue
        content = msg["content"]
        # Split off the per-turn prefix (a [drives: ...] block) if present
        if "\n\n[drives:" in content:
            _, prefix = content.split("\n\n[drives:", 1)
            new_content = f"{VITALITY_SYSTEM_BASE}\n\n[drives:{prefix}"
        else:
            new_content = VITALITY_SYSTEM_BASE
        msgs[i] = {"role": "system", "content": new_content}
        break
    return {**ex, "messages": msgs}


def detect_tank_in_meta(ex: dict, target_tanks: set[str]) -> str | None:
    """Return tank name if this example targets one of target_tanks (via _meta or system prompt sniff)."""
    meta = ex.get("_meta") or {}
    tank = meta.get("tank")
    if tank and tank in target_tanks:
        return tank
    # Sniff system prompt for high-state tank prefix tokens
    msgs = ex.get("messages", [])
    sys_msg = next((m["content"] for m in msgs if m["role"] == "system"), "")
    for t in target_tanks:
        # high-state prefix typically has "<tank>=0.85" or "<tank>=0.75"
        if f"{t}=0.7" in sys_msg or f"{t}=0.8" in sys_msg or f"{t}=0.9" in sys_msg:
            return t
    return None


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--data-dir", default="data/training")
    p.add_argument("--out-prefix", default="vitality_v2")
    p.add_argument("--upweight-standing", type=int, default=1,
                   help="Duplicate standing-EN-high turns N times (default 1=no upweight)")
    p.add_argument("--upweight-tanks", nargs="+",
                   default=["standing", "startle", "surprise", "loneliness", "restlessness"],
                   help="Tanks to upweight (only EN high-state turns)")
    p.add_argument("--upweight-multiturn", type=int, default=5,
                   help="Duplicate V5 multi-turn examples N times (default 5×)")
    args = p.parse_args()

    data_dir = Path(args.data_dir)

    balanced_train = load_jsonl(data_dir / "balanced_train.jsonl")
    balanced_valid = load_jsonl(data_dir / "balanced_valid.jsonl")
    vitality_train = load_jsonl(data_dir / "vitality_train.jsonl")
    vitality_valid = load_jsonl(data_dir / "vitality_valid.jsonl")

    print(f"Loaded:")
    print(f"  balanced_train: {len(balanced_train)} turns")
    print(f"  balanced_valid: {len(balanced_valid)} turns")
    print(f"  vitality_train: {len(vitality_train)} turns")
    print(f"  vitality_valid: {len(vitality_valid)} turns")

    if not balanced_train or not vitality_train:
        print("ERROR: missing required corpus files", file=sys.stderr)
        sys.exit(1)

    # Rewrite balanced system prompts to vitality vocab
    balanced_train = [rewrite_system_prompt_to_vitality(ex) for ex in balanced_train]
    balanced_valid = [rewrite_system_prompt_to_vitality(ex) for ex in balanced_valid]

    # Combine + apply upweighting on critical-pattern tanks BEFORE shuffle.
    # Each high-state EN turn for an upweight target gets duplicated N-1 times
    # (so N=2 means original + 1 duplicate = 2x weight at training time).
    target_tanks = set(args.upweight_tanks)
    if args.upweight_standing > 1 or args.upweight_multiturn > 1:
        upweighted = []
        upweight_counts = {t: 0 for t in target_tanks}
        multiturn_count = 0
        for ex in vitality_train:
            meta = ex.get("_meta") or {}
            # Multi-turn examples upweighted regardless of single-turn rules.
            # They fix the multi-turn failure surface and need stronger gradient
            # signal vs ~1800 single-turn baseline. V6 deltas serve the same
            # purpose and must be upweighted equally — they target specific
            # failure modes V5 didn't fully close.
            is_multiturn = meta.get("source") in (
                "multiturn_v5", "multiturn_v6_deltas",
            )
            tank = detect_tank_in_meta(ex, target_tanks)
            is_en_high = meta.get("lang") == "en" and meta.get("state") == "high"
            if is_multiturn:
                for _ in range(args.upweight_multiturn):
                    upweighted.append(dict(ex))
                multiturn_count += 1
            elif tank and is_en_high and args.upweight_standing > 1:
                for _ in range(args.upweight_standing):
                    upweighted.append(dict(ex))
                upweight_counts[tank] += 1
            else:
                upweighted.append(ex)
        vitality_train = upweighted
        added = sum((args.upweight_standing - 1) * c for c in upweight_counts.values())
        print(f"\nUpweighted EN-high tanks {sorted(target_tanks)} ×{args.upweight_standing}: "
              f"+{added} turns")
        for t, c in sorted(upweight_counts.items()):
            if c:
                print(f"  {t}: {c} originals × {args.upweight_standing} = {c * args.upweight_standing}")
        if multiturn_count:
            mt_added = (args.upweight_multiturn - 1) * multiturn_count
            print(f"Upweighted multi-turn V5 examples ×{args.upweight_multiturn}: "
                  f"{multiturn_count} originals → {multiturn_count * args.upweight_multiturn} "
                  f"(+{mt_added} duplicates)")

    combined_train = balanced_train + vitality_train
    combined_valid = balanced_valid + vitality_valid
    random.shuffle(combined_train)
    random.shuffle(combined_valid)

    # Strip _meta from vitality turns (SSD pipeline expects pure messages)
    for ex in combined_train + combined_valid:
        ex.pop("_meta", None)

    out_train = data_dir / f"{args.out_prefix}_train.jsonl"
    out_valid = data_dir / f"{args.out_prefix}_valid.jsonl"
    with open(out_train, "w") as f:
        for ex in combined_train:
            f.write(json.dumps(ex, ensure_ascii=False) + "\n")
    with open(out_valid, "w") as f:
        for ex in combined_valid:
            f.write(json.dumps(ex, ensure_ascii=False) + "\n")

    print(f"\nWrote:")
    print(f"  {out_train}: {len(combined_train)} turns")
    print(f"  {out_valid}: {len(combined_valid)} turns")
    print(f"  total: {len(combined_train) + len(combined_valid)}")


if __name__ == "__main__":
    main()
