"""V8 smoke-test gate — does Qwen3.5-4B accept llama.cpp control vectors?

Steps:
1. Load Qwen3.5-4B HF weights with ControlModel wrapper
2. Train a tiny dummy vector on 30 happy/sad pairs
3. Export GGUF
4. Spawn llama-server with --control-vector-scaled
5. Compare outputs at α=0 vs α=2.0 — they should differ

Pass criteria:
- Vector trains without error
- llama-server loads vector without error
- Outputs at high α visibly differ from α=0 baseline

If this fails: V8 plan needs revision (different tooling or different base model).
"""

import json
import os
import subprocess
import sys
import time
from pathlib import Path

import torch
from transformers import AutoModelForCausalLM, AutoTokenizer
from repeng import ControlVector, ControlModel, DatasetEntry

ROOT = Path("/home/you/src/wyrdsekai")
HF_DIR = ROOT / "data/training/v8/qwen3.5-4b-hf"
WORK_DIR = ROOT / "data/training/v8"
DUMMY_VEC = WORK_DIR / "vectors/smoketest-happy.gguf"
GGUF_4B = ROOT / "data/models/4b-vitality-v5-q4km.gguf"

# Tiny pair set: happy/sad — known to steer for any decent base model.
DUMMY_PAIRS = [
    DatasetEntry(positive="I'm thrilled to be here today.",
                 negative="I'm miserable being here today."),
    DatasetEntry(positive="What a wonderful, joyful day this has been.",
                 negative="What a dreadful, gloomy day this has been."),
    DatasetEntry(positive="The good news lifts my spirit completely.",
                 negative="The bad news drags my spirit down."),
    DatasetEntry(positive="I love how this is going.",
                 negative="I hate how this is going."),
    DatasetEntry(positive="Everything feels alive and bright.",
                 negative="Everything feels dead and bleak."),
    DatasetEntry(positive="My heart is full of gratitude.",
                 negative="My heart is full of resentment."),
    DatasetEntry(positive="This is the best moment of the week.",
                 negative="This is the worst moment of the week."),
    DatasetEntry(positive="I'm beaming with happiness right now.",
                 negative="I'm fuming with anger right now."),
    DatasetEntry(positive="Such delightful company we have today.",
                 negative="Such tedious company we have today."),
    DatasetEntry(positive="I cannot stop smiling about this.",
                 negative="I cannot stop frowning about this."),
] * 3  # 30 pairs


def main():
    if not HF_DIR.exists() or not any(HF_DIR.iterdir()):
        sys.exit(f"HF weights missing at {HF_DIR}. Download first.")
    if not GGUF_4B.exists():
        sys.exit(f"4B GGUF missing at {GGUF_4B}")

    DUMMY_VEC.parent.mkdir(parents=True, exist_ok=True)

    print(f"[smoke] Loading Qwen3.5-4B from {HF_DIR}")
    tokenizer = AutoTokenizer.from_pretrained(str(HF_DIR), trust_remote_code=True)
    model = AutoModelForCausalLM.from_pretrained(
        str(HF_DIR),
        torch_dtype=torch.bfloat16,
        device_map="cuda",
        trust_remote_code=True,
    )
    print(f"[smoke] Model loaded. dtype={model.dtype} device={model.device}")
    print(f"[smoke] Wrapping with ControlModel (layers 8-24)")
    cm = ControlModel(model, layer_ids=list(range(8, 24)))

    print(f"[smoke] Training control vector on {len(DUMMY_PAIRS)} pairs...")
    t0 = time.time()
    vec = ControlVector.train(cm, tokenizer, DUMMY_PAIRS)
    print(f"[smoke] Training done in {time.time()-t0:.1f}s")

    print(f"[smoke] Exporting to GGUF: {DUMMY_VEC}")
    vec.export_gguf(str(DUMMY_VEC))
    print(f"[smoke] GGUF size: {DUMMY_VEC.stat().st_size} bytes")

    # Free GPU before invoking llama-server
    del cm, model
    torch.cuda.empty_cache()

    print("[smoke] Now run scripts/training/v8/smoke_test_llama.sh to verify "
          "llama-server loads the vector and changes outputs.")
    print(f"[smoke] Vector path: {DUMMY_VEC}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
