#!/usr/bin/env python3
"""
L2 vector pipeline · step 3 · run repeng on gpu-host against Granite-Substrate-3B.

Reads pairs.jsonl, loads the merged HF weights, wraps with ControlModel over
the chosen layer range, extracts the control vector, exports as a llama.cpp
GGUF that can be loaded via --control-vector.

Run on gpu-host:
  source /home/you/.codeplane/sglang-venv/bin/activate
  python 03_extract_vector.py --layers 8 32
"""

import argparse
import json
import sys
import time
from pathlib import Path

import torch
from transformers import AutoModelForCausalLM, AutoTokenizer
from repeng import ControlVector, ControlModel, DatasetEntry


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--hf-dir", default="/home/you/wyrdsekai-granite-3b-substrate-v1-merged")
    ap.add_argument("--pairs", required=True, help="Path to pairs.jsonl (positive/negative)")
    ap.add_argument("--output", required=True, help="Output GGUF path")
    ap.add_argument("--layers", type=int, nargs=2, default=(8, 32),
                    metavar=("START", "END"),
                    help="Layer range [start, end) for ControlModel hooks")
    ap.add_argument("--device", default="cuda")
    args = ap.parse_args()

    pairs_path = Path(args.pairs)
    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    entries = []
    with pairs_path.open() as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            obj = json.loads(line)
            if not obj.get("positive") or not obj.get("negative"):
                continue
            entries.append(DatasetEntry(positive=obj["positive"], negative=obj["negative"]))
    print(f"[extract] loaded {len(entries)} pairs from {pairs_path}", flush=True)

    if len(entries) < 10:
        sys.exit(f"[extract] need ≥10 pairs, got {len(entries)}")

    print(f"[extract] loading HF model from {args.hf_dir}", flush=True)
    tokenizer = AutoTokenizer.from_pretrained(args.hf_dir, trust_remote_code=True)
    model = AutoModelForCausalLM.from_pretrained(
        args.hf_dir,
        dtype=torch.bfloat16,
        device_map=args.device,
        trust_remote_code=True,
    )
    n_layers = getattr(model.config, "num_hidden_layers", None)
    print(f"[extract] model loaded — {n_layers} layers; wrapping {args.layers[0]}-{args.layers[1]}", flush=True)
    cm = ControlModel(model, layer_ids=list(range(args.layers[0], args.layers[1])))

    print(f"[extract] training control vector on {len(entries)} pairs…", flush=True)
    t0 = time.time()
    vec = ControlVector.train(cm, tokenizer, entries)
    elapsed = time.time() - t0
    print(f"[extract] trained in {elapsed:.1f}s", flush=True)

    print(f"[extract] exporting GGUF: {output_path}", flush=True)
    vec.export_gguf(str(output_path))
    size_mb = output_path.stat().st_size / 1e6
    print(f"[extract] DONE — {output_path} ({size_mb:.2f} MB)", flush=True)


if __name__ == "__main__":
    main()
