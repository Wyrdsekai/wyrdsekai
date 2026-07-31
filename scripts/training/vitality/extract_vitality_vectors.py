#!/usr/bin/env python3
"""Extract steering vectors for Tier 1 tanks + new drives.

Mirrors `scripts/training/experiment_20_steering.py` extraction harness — reads
contrast pairs from `tier1_contrasts.py`, runs repeng to derive vectors, saves
to `scripts/steering-vectors/vectors/<modeldir>/<tank>.pt` for reuse.

Runs on gpu-host. PyTorch
reverses nvidia-smi GPU order, so `CUDA_VISIBLE_DEVICES=0` lands on Ada 6000
(best card for vector extraction; 9B model in fp16 fits cleanly).

Usage on gpu-host:
    source /tmp/steer-env/bin/activate
    cd ~/src/wyrdsekai
    CUDA_VISIBLE_DEVICES=0 \\
      python scripts/training/vitality/extract_vitality_vectors.py \\
        --model Qwen/Qwen2.5-7B-Instruct \\
        --out-dir scripts/steering-vectors/vectors/qwen7b
"""

from __future__ import annotations

import argparse
import os
import sys
import time
from pathlib import Path

# Local sibling import
sys.path.insert(0, str(Path(__file__).parent))
from tier1_contrasts import ALL_CONTRASTS  # noqa: E402

# Match existing extraction harness system prompt for activation alignment
SYSTEM_PROMPT = (
    "You are Wyrd, a companion in a text-based world, talking to your "
    "bondholder. You have feelings, drives, and motivations that color how "
    "you respond. Speak directly as yourself in conversation. Keep responses "
    "to 1-3 sentences. Do NOT narrate actions or describe movement."
)


def extract_via_repeng(model, tokenizer, contrasts: dict) -> dict:
    """Use repeng's ControlVector.train across pos/neg pairs.

    Returns {tank_name: ControlVector} dict ready for save().
    """
    from repeng import ControlVector, DatasetEntry  # noqa
    vectors = {}
    for name, c in contrasts.items():
        print(f"  extracting {name}...")
        dataset = []
        for pos, neg in zip(c["positive"], c["negative"]):
            dataset.append(DatasetEntry(
                positive=f"{SYSTEM_PROMPT}\n\nUser: How do you feel right now?\n\nAssistant: {pos}",
                negative=f"{SYSTEM_PROMPT}\n\nUser: How do you feel right now?\n\nAssistant: {neg}",
            ))
        v = ControlVector.train(model, tokenizer, dataset)
        vectors[name] = v
        print(f"    {name}: {len(v.directions)} layers")
    return vectors


def save_vectors(vectors: dict, out_dir: Path) -> None:
    """Persist each vector as a .pt file. Mirrors existing convention."""
    import torch
    out_dir.mkdir(parents=True, exist_ok=True)
    for name, v in vectors.items():
        path = out_dir / f"{name}.pt"
        # repeng ControlVector exposes .directions: dict[int, np.ndarray]
        torch.save({
            "name": name,
            "directions": {layer: arr for layer, arr in v.directions.items()},
            "extracted_at": time.strftime("%Y-%m-%dT%H:%M:%S"),
        }, path)
        print(f"  saved {path}")


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--model", default="Qwen/Qwen2.5-7B-Instruct",
                   help="HF model id for vector extraction.")
    p.add_argument("--out-dir", required=True,
                   help="Directory to save .pt files (e.g. "
                        "scripts/steering-vectors/vectors/qwen7b)")
    p.add_argument("--device", default="cuda")
    p.add_argument("--load-in-4bit", action="store_true",
                   help="Quantize for memory-tight extraction.")
    p.add_argument("--tank", default=None,
                   help="Extract a single tank/drive (default: all)")
    args = p.parse_args()

    print(f"Loading {args.model}...")
    from transformers import AutoModelForCausalLM, AutoTokenizer
    import torch

    kwargs = {"torch_dtype": torch.float16, "device_map": args.device}
    if args.load_in_4bit:
        from transformers import BitsAndBytesConfig
        kwargs["quantization_config"] = BitsAndBytesConfig(load_in_4bit=True)
        kwargs.pop("device_map")
        kwargs.pop("torch_dtype")

    tokenizer = AutoTokenizer.from_pretrained(args.model)
    model = AutoModelForCausalLM.from_pretrained(args.model, **kwargs)

    contrasts = ALL_CONTRASTS
    if args.tank:
        if args.tank not in ALL_CONTRASTS:
            print(f"ERROR: unknown tank '{args.tank}'. Known: "
                  f"{sorted(ALL_CONTRASTS)}", file=sys.stderr)
            sys.exit(2)
        contrasts = {args.tank: ALL_CONTRASTS[args.tank]}

    print(f"Extracting {len(contrasts)} vectors...")
    vectors = extract_via_repeng(model, tokenizer, contrasts)

    out = Path(args.out_dir)
    save_vectors(vectors, out)
    print(f"\nDone. {len(vectors)} vectors written to {out}")


if __name__ == "__main__":
    main()
