#!/usr/bin/env python3
"""Build train/valid splits from substrate_raw.jsonl with stratified sampling.

Reads:
  data/training/substrate/substrate_raw.jsonl   (output of generate_substrate_corpus.py)
  data/training/vitality_train.jsonl            (optional preservation mix)

Writes:
  data/training/substrate_train.jsonl
  data/training/substrate_valid.jsonl

Why stratify: substrate corpus has 5 actions × 3 langs = 15 cells. Plain
random 90/10 leaves some cells with 0-1 valid samples, making per-cell
validation noisy. Per-bucket 90/10 ensures each (action, lang) cell has
≥1 valid sample (assuming the bucket has ≥10 examples).

Preservation mix: if `--preservation-frac` is set, samples that fraction
of substrate's train size from V7 vitality_train and merges them in. This
regularizes against substrate-only overfit — model retains existing
vitality behaviors. Default 0.15 (15% of substrate size).

Usage:
    python scripts/training/substrate/build_substrate_corpus.py
    python scripts/training/substrate/build_substrate_corpus.py --preservation-frac 0.0  # substrate only
    python scripts/training/substrate/build_substrate_corpus.py --preservation-frac 0.25  # heavier preservation
    python scripts/training/substrate/build_substrate_corpus.py --strip-meta  # for SSD trainer
"""

from __future__ import annotations

import argparse
import json
import random
import sys
from collections import defaultdict
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
DEFAULT_RAW = REPO / "data/training/substrate/substrate_raw.jsonl"
DEFAULT_PRESERVATION = REPO / "data/training/vitality_train.jsonl"
DEFAULT_OUT_DIR = REPO / "data/training"


def load_jsonl(path: Path) -> list[dict]:
    out = []
    with path.open() as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            out.append(json.loads(line))
    return out


def write_jsonl(path: Path, rows: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")


def stratified_split(rows: list[dict], split: float, rng: random.Random
                      ) -> tuple[list[dict], list[dict]]:
    """Per-(action, lang) bucket 90/10 split. Buckets with <10 rows put at
    least 1 in valid (round down for train, ceil for valid)."""
    buckets: dict[tuple[str, str], list[dict]] = defaultdict(list)
    for r in rows:
        meta = r.get("_meta", {})
        key = (meta.get("action", "?"), meta.get("lang", "?"))
        buckets[key].append(r)

    train, valid = [], []
    for key in sorted(buckets):
        bucket = buckets[key]
        rng.shuffle(bucket)
        n = len(bucket)
        # At least 1 valid sample per bucket if bucket has >= 5 rows
        n_train = max(1, int(round(n * split)))
        if n >= 5 and n_train == n:
            n_train = n - 1
        train.extend(bucket[:n_train])
        valid.extend(bucket[n_train:])
    return train, valid


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--raw", type=Path, default=DEFAULT_RAW,
                    help="Path to substrate_raw.jsonl")
    ap.add_argument("--preservation", type=Path, default=DEFAULT_PRESERVATION,
                    help="Path to V7 vitality_train.jsonl for preservation mix")
    ap.add_argument("--preservation-frac", type=float, default=0.15,
                    help="Fraction of substrate-train size to sample from "
                         "preservation source (default 0.15)")
    ap.add_argument("--out-dir", type=Path, default=DEFAULT_OUT_DIR)
    ap.add_argument("--prefix", default="substrate",
                    help="Output filename prefix (default 'substrate')")
    ap.add_argument("--split", type=float, default=0.9)
    ap.add_argument("--seed", type=int, default=20260515)
    ap.add_argument("--strip-meta", action="store_true",
                    help="Drop _meta from output rows (SSD trainer doesn't need it)")
    args = ap.parse_args()

    rng = random.Random(args.seed)

    # ── Load substrate corpus
    if not args.raw.exists():
        print(f"ERROR: {args.raw} not found. Run generate_substrate_corpus.py first.",
              file=sys.stderr)
        sys.exit(2)
    substrate = load_jsonl(args.raw)
    print(f"Loaded {len(substrate)} substrate examples from {args.raw}")

    # ── Per-bucket breakdown
    cells: dict[tuple[str, str], int] = defaultdict(int)
    for r in substrate:
        meta = r.get("_meta", {})
        cells[(meta.get("action", "?"), meta.get("lang", "?"))] += 1
    actions = sorted({k[0] for k in cells})
    langs = sorted({k[1] for k in cells})
    print("\nSubstrate per-(action, lang) cell counts:")
    header = "action".ljust(35) + " | " + " | ".join(f"{l:>4s}" for l in langs) + " | total"
    print(header)
    print("-" * len(header))
    for action in actions:
        row = action.ljust(35) + " | "
        total = 0
        for lang in langs:
            n = cells.get((action, lang), 0)
            total += n
            row += f"{n:>4d} | "
        row += f"{total:>5d}"
        print(row)

    # ── Stratified split
    train, valid = stratified_split(substrate, args.split, rng)
    print(f"\nSubstrate split: {len(train)} train / {len(valid)} valid")

    # ── Optional preservation mix
    if args.preservation_frac > 0 and args.preservation.exists():
        n_pres = int(round(len(train) * args.preservation_frac))
        all_pres = load_jsonl(args.preservation)
        rng.shuffle(all_pres)
        pres_sample = all_pres[:n_pres]
        # Stamp source meta so we can tell preservation rows apart
        for r in pres_sample:
            meta = r.setdefault("_meta", {})
            meta["substrate_role"] = "preservation"
        train.extend(pres_sample)
        # Also bring some preservation into valid (proportional small share)
        n_pres_valid = max(0, int(round(len(valid) * args.preservation_frac)))
        valid.extend(all_pres[n_pres:n_pres + n_pres_valid])
        for r in all_pres[n_pres:n_pres + n_pres_valid]:
            meta = r.setdefault("_meta", {})
            meta["substrate_role"] = "preservation"
        print(f"Preservation mix: +{n_pres} train, +{n_pres_valid} valid "
              f"(from {args.preservation})")
    elif args.preservation_frac > 0:
        print(f"Preservation skipped: {args.preservation} not found")

    # ── Shuffle final splits + strip meta if requested
    rng.shuffle(train)
    rng.shuffle(valid)
    if args.strip_meta:
        for r in train + valid:
            r.pop("_meta", None)

    # ── Write
    train_path = args.out_dir / f"{args.prefix}_train.jsonl"
    valid_path = args.out_dir / f"{args.prefix}_valid.jsonl"
    write_jsonl(train_path, train)
    write_jsonl(valid_path, valid)

    print(f"\nWrote {len(train)} train → {train_path}")
    print(f"Wrote {len(valid)} valid → {valid_path}")

    # ── Final breakdown
    role_counts: dict[str, int] = defaultdict(int)
    for split_name, rows in [("train", train), ("valid", valid)]:
        for r in rows:
            role = r.get("_meta", {}).get("substrate_role", "substrate")
            role_counts[f"{split_name}/{role}"] += 1
    print("\nFinal role breakdown:")
    for k, v in sorted(role_counts.items()):
        print(f"  {k:30s} {v}")


if __name__ == "__main__":
    main()
