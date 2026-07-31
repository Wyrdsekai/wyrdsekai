#!/usr/bin/env python3
"""Aggregate all vitality corpus sources into a single SSD train/valid split.

Sources (all from data/training/vitality/):
  Tier 2 (cultural, all 3 langs from Opus 4.7):
    amae.jsonl, saudade.jsonl, obligation.jsonl, harmony.jsonl, standing.jsonl
  Tier 1 EN refs (Sonnet 4.6):
    *_pilot.jsonl
  Tier 1 EN steered (Qwen3.5-9B + steering vectors):
    *_steered.jsonl
  Tier 1 JA/ES (trans-created via Sonnet 4.6):
    *_ja.jsonl, *_es.jsonl

Per spec §11 the total target is ~680 turns. We aggregate all available,
shuffle deterministically, and 90/10 train/valid split.

Usage:
    python scripts/training/vitality/build_vitality_corpus.py \\
        --in-dir data/training/vitality \\
        --out-dir data/training
"""

from __future__ import annotations

import argparse
import json
import random
import sys
from collections import defaultdict
from pathlib import Path

random.seed(42)


def load_jsonl(path: Path) -> list[dict]:
    out = []
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            out.append(json.loads(line))
    return out


def write_jsonl(path: Path, rows: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")


def collect(in_dir: Path) -> tuple[list[dict], dict]:
    """Walk the vitality dir, classify each turn by source, return all + stats."""
    all_turns = []
    stats = defaultdict(lambda: defaultdict(int))

    for path in sorted(in_dir.glob("*.jsonl")):
        name = path.stem  # e.g. "amae" or "restlessness_pilot" or "loneliness_es"
        for ex in load_jsonl(path):
            meta = ex.get("_meta", {})
            tank = meta.get("tank", "?")
            lang = meta.get("lang", "?")
            source = meta.get("source", "")
            if not source:
                # Infer source from filename suffix
                if name.endswith("_pilot"):
                    source = "ref"
                elif name.endswith("_steered"):
                    source = "steered"
                elif name.endswith("_ja") or name.endswith("_es"):
                    source = "trans_created"
                else:
                    source = "tier2_native"
            stats[tank][lang] += 1
            stats[tank][f"src:{source}"] += 1
            all_turns.append(ex)

    return all_turns, stats


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--in-dir", default="data/training/vitality")
    p.add_argument("--out-dir", default="data/training")
    p.add_argument("--split", type=float, default=0.9,
                   help="Train fraction (default 0.9)")
    p.add_argument("--prefix", default="vitality",
                   help="Output filename prefix (default 'vitality')")
    p.add_argument("--strip-meta", action="store_true",
                   help="Strip _meta fields from output (keeps SSD input clean)")
    args = p.parse_args()

    in_dir = Path(args.in_dir)
    out_dir = Path(args.out_dir)

    print(f"Collecting from {in_dir}...")
    turns, stats = collect(in_dir)
    print(f"\nTotal turns: {len(turns)}")
    print(f"\nPer-tank/lang/source breakdown:")
    for tank in sorted(stats):
        breakdown = ", ".join(
            f"{k}={v}" for k, v in sorted(stats[tank].items())
            if not k.startswith("src:")
        )
        sources = ", ".join(
            f"{k[4:]}={v}" for k, v in sorted(stats[tank].items())
            if k.startswith("src:")
        )
        print(f"  {tank:25s}  {breakdown}  [{sources}]")

    # Shuffle deterministically and split
    random.shuffle(turns)
    if args.strip_meta:
        for t in turns:
            t.pop("_meta", None)

    n_train = int(round(len(turns) * args.split))
    train = turns[:n_train]
    valid = turns[n_train:]

    train_path = out_dir / f"{args.prefix}_train.jsonl"
    valid_path = out_dir / f"{args.prefix}_valid.jsonl"
    write_jsonl(train_path, train)
    write_jsonl(valid_path, valid)

    print(f"\nWrote {len(train)} train → {train_path}")
    print(f"Wrote {len(valid)} valid → {valid_path}")


if __name__ == "__main__":
    main()
