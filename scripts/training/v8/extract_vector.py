"""V8 vector extraction — production wrapper around repeng.

Loads pair JSONL produced by generate_pairs.py, runs repeng on Qwen3.5-4B HF
weights, exports the result as a llama.cpp-compatible GGUF.

Prereq: stop wyrdsekai-llama and wyrdsekai-llama-voice on home-server to free GPU
(this script does NOT manage them — operator's responsibility).

Usage:
  /home/you/venvs/v8-steering/bin/python scripts/training/v8/extract_vector.py \\
      --vector anti_defiance --layers 8 24

Output: data/training/v8/vectors/<vector>.gguf
"""
import argparse
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from _v8_lib import VECTORS, HF_DIR, PAIRS_DIR, VECTORS_DIR

import torch
from transformers import AutoModelForCausalLM, AutoTokenizer
from repeng import ControlVector, ControlModel, DatasetEntry


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--vector", required=True, choices=list(VECTORS.keys()))
    ap.add_argument("--pairs", default=None,
                    help="Override pair JSONL path (default: pairs/<vector>.jsonl)")
    ap.add_argument("--output", default=None,
                    help="Override output GGUF path (default: vectors/<vector>.gguf)")
    ap.add_argument("--layers", type=int, nargs=2, default=(8, 24),
                    metavar=("START", "END"),
                    help="Layer range for ControlModel hooks (inclusive start, exclusive end)")
    ap.add_argument("--device", default="cuda")
    args = ap.parse_args()

    pairs_path = Path(args.pairs) if args.pairs else PAIRS_DIR / f"{args.vector}.jsonl"
    output_path = Path(args.output) if args.output else VECTORS_DIR / f"{args.vector}.gguf"

    if not pairs_path.exists():
        sys.exit(f"[extract] pair file missing: {pairs_path}. Run generate_pairs.py first.")

    output_path.parent.mkdir(parents=True, exist_ok=True)

    # Load pairs
    pairs = []
    with pairs_path.open() as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            obj = json.loads(line)
            pairs.append(DatasetEntry(positive=obj["positive"], negative=obj["negative"]))
    print(f"[extract] loaded {len(pairs)} pairs from {pairs_path}")

    if len(pairs) < 10:
        sys.exit(f"[extract] need ≥10 pairs, got {len(pairs)}")

    # Load model
    print(f"[extract] loading Qwen3.5-4B from {HF_DIR}")
    tokenizer = AutoTokenizer.from_pretrained(str(HF_DIR), trust_remote_code=True)
    model = AutoModelForCausalLM.from_pretrained(
        str(HF_DIR),
        dtype=torch.bfloat16,
        device_map=args.device,
        trust_remote_code=True,
    )
    print(f"[extract] model loaded. wrapping ControlModel layers {args.layers[0]}-{args.layers[1]}")
    cm = ControlModel(model, layer_ids=list(range(args.layers[0], args.layers[1])))

    # Train
    print(f"[extract] training control vector on {len(pairs)} pairs...")
    t0 = time.time()
    vec = ControlVector.train(cm, tokenizer, pairs)
    elapsed = time.time() - t0
    print(f"[extract] trained in {elapsed:.1f}s")

    # Export
    print(f"[extract] exporting GGUF: {output_path}")
    vec.export_gguf(str(output_path))
    size_mb = output_path.stat().st_size / 1e6
    print(f"[extract] DONE — {output_path} ({size_mb:.2f} MB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
