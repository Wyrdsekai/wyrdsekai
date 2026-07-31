#!/usr/bin/env python3
# recipe-callable: local-ok
"""Recipe-callable parity probe for a freshly-extracted steering vector.

Companion to extract_steering_vector.py. Loads a small held-out probe set
(prompts NOT in the training pairs), runs the voice model both WITH and
WITHOUT the candidate vector applied, computes a parity score from the
two completion sets, and emits the delta as the welfare-gate signal.

The parity score is a coarse "voice still works" signal — high score
means the completions are still well-formed text in the right style;
low means the vector destabilized the model. We require
`parity_delta >= -parity_max_regression` to deploy (a 5% tolerance by
default, configured via the recipe param).

JSONL probe format (one row per line):
    {"prompt": "<question or instruction>"}

Output (stdout, single JSON line):
    {"vector": "<name>", "probes_tested": N,
     "parity_baseline": <0..1>, "parity_score": <0..1>,
     "parity_delta": <signed diff baseline→candidate>}
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


def load_probes(path: Path) -> list[str]:
    prompts = []
    with path.open() as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            row = json.loads(line)
            prompts.append(row["prompt"])
    return prompts


def score_completion(text: str) -> float:
    """Heuristic 'voice still works' score in [0,1].

    Cheap signals that the model didn't collapse:
     - completion is non-empty after trimming
     - no obvious degenerate repetition (any 4-token window repeated 3+ times)
     - reasonable length (≥20 chars, ≤2000)
     - no NaN-token leak (no `<unk>` or replacement chars)

    Each signal contributes 0.25; full = 1.0, total collapse = 0.0.
    Tuned empirically against the V9 4B greeting-collapse failure
    (catastrophic specialization → all replies = "Here.").
    """
    if not text:
        return 0.0
    score = 0.0
    t = text.strip()
    if t:
        score += 0.25
    tokens = t.split()
    if 20 <= len(t) <= 2000:
        score += 0.25
    if len(tokens) >= 4:
        # Check 4-gram repetition. If any 4-gram repeats ≥3 times → collapse.
        ngrams: dict[str, int] = {}
        for i in range(len(tokens) - 3):
            g = " ".join(tokens[i:i + 4])
            ngrams[g] = ngrams.get(g, 0) + 1
        if max(ngrams.values(), default=1) < 3:
            score += 0.25
    else:
        # Short reply but in-bounds — credit if no obvious leak chars.
        score += 0.25
    if "<unk>" not in t and "�" not in t:
        score += 0.25
    return min(1.0, score)


def generate_with(model, tokenizer, prompts, vector=None, device="cuda"):
    completions = []
    for p in prompts:
        ids = tokenizer(p, return_tensors="pt").to(device)
        # If `model` is a repeng ControlModel and vector is provided,
        # apply it; else just generate vanilla.
        if vector is not None and hasattr(model, "set_control"):
            model.set_control(vector, coeff=1.0)
        try:
            out = model.generate(**ids, max_new_tokens=80, do_sample=False)
        finally:
            if vector is not None and hasattr(model, "reset"):
                model.reset()
        completions.append(tokenizer.decode(out[0], skip_special_tokens=True))
    return completions


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--vector", required=True)
    ap.add_argument("--vector-path", required=True, type=Path,
                    help="Candidate GGUF to evaluate.")
    ap.add_argument("--probes-path", required=True, type=Path)
    ap.add_argument("--voice-model", type=Path, default=Path("data/models/voice-4b"))
    args = ap.parse_args()

    if not args.probes_path.exists():
        # Missing probe set: emit a structured failure (the recipe gate
        # decides whether absence is a hard fail or a "skip with warn").
        print(json.dumps({
            "vector": args.vector,
            "probes_tested": 0,
            "parity_baseline": 0.0,
            "parity_score": 0.0,
            "parity_delta": -1.0,  # forces gate to fail
            "error": "probes_missing",
        }))
        sys.exit(0)

    prompts = load_probes(args.probes_path)
    if not prompts:
        print(json.dumps({
            "vector": args.vector, "probes_tested": 0,
            "parity_baseline": 0.0, "parity_score": 0.0,
            "parity_delta": -1.0, "error": "probes_empty",
        }))
        sys.exit(0)

    try:
        import torch
        from transformers import AutoModelForCausalLM, AutoTokenizer
        from repeng import ControlVector, ControlModel
    except ImportError as e:
        # No torch/repeng → can't probe. Emit structured failure that
        # forces the gate to STOP (don't deploy without parity probe).
        print(json.dumps({
            "vector": args.vector, "probes_tested": 0,
            "parity_baseline": 0.0, "parity_score": 0.0,
            "parity_delta": -1.0,
            "error": "missing_deps", "error_detail": str(e),
        }))
        sys.exit(0)

    device = "cuda" if torch.cuda.is_available() else "cpu"
    tokenizer = AutoTokenizer.from_pretrained(str(args.voice_model))
    model = AutoModelForCausalLM.from_pretrained(
        str(args.voice_model),
        torch_dtype=torch.bfloat16 if device == "cuda" else torch.float32,
    ).to(device)
    cm = ControlModel(model, layer_ids=list(range(8, 24)))
    vector = ControlVector.import_gguf(str(args.vector_path))

    baseline_completions = generate_with(cm, tokenizer, prompts, vector=None, device=device)
    candidate_completions = generate_with(cm, tokenizer, prompts, vector=vector, device=device)

    baseline_score = sum(score_completion(c) for c in baseline_completions) / len(prompts)
    candidate_score = sum(score_completion(c) for c in candidate_completions) / len(prompts)

    print(json.dumps({
        "vector": args.vector,
        "probes_tested": len(prompts),
        "parity_baseline": baseline_score,
        "parity_score": candidate_score,
        "parity_delta": candidate_score - baseline_score,
    }))


if __name__ == "__main__":
    main()
