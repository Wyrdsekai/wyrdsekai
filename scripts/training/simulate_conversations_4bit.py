#!/usr/bin/env python3
"""
Simulate conversations using a 4-bit quantized model for 16GB VRAM cards.
Same as simulate_conversations.py but loads model in 4-bit via bitsandbytes.
"""

import argparse
import json
import random
import time
import torch
from transformers import AutoModelForCausalLM, AutoTokenizer, BitsAndBytesConfig

from experiment_20_steering import (
    DRIVE_CONTRASTS, SYSTEM_PROMPT, KEYWORD_SETS,
    extract_vectors_repeng, generate_with_vector
)
from simulate_conversations import (
    SCENARIOS, intensity_to_scale, build_drive_prefix, run_simulation
)


def load_model_4bit(model_name, device="cuda"):
    """Load model in 4-bit quantization."""
    print(f"Loading {model_name} in 4-bit...")
    tokenizer = AutoTokenizer.from_pretrained(model_name, trust_remote_code=True)

    bnb_config = BitsAndBytesConfig(
        load_in_4bit=True,
        bnb_4bit_quant_type="nf4",
        bnb_4bit_compute_dtype=torch.float16,
        bnb_4bit_use_double_quant=True,
    )

    model = AutoModelForCausalLM.from_pretrained(
        model_name,
        quantization_config=bnb_config,
        device_map="auto",
        trust_remote_code=True,
    )
    model.eval()
    print(f"Model loaded in 4-bit: {model.config.num_hidden_layers} layers")
    return model, tokenizer


def main():
    parser = argparse.ArgumentParser(description="Generate drive-tagged conversations (4-bit)")
    parser.add_argument("--model", default="Qwen/Qwen3.5-9B")
    parser.add_argument("--output", default="tagged_conversations_thinking.jsonl")
    parser.add_argument("--repetitions", type=int, default=10)
    args = parser.parse_args()

    model, tokenizer = load_model_4bit(args.model)

    print("\nExtracting steering vectors (4-bit model)...")
    vectors = extract_vectors_repeng(model, tokenizer, "cuda")

    print(f"\nRunning simulation ({len(SCENARIOS)} scenarios × {args.repetitions} reps)...")
    turns = run_simulation(model, tokenizer, vectors, "cuda", SCENARIOS, args.repetitions)

    with open(args.output, "w") as f:
        for turn in turns:
            f.write(json.dumps(turn) + "\n")

    by_drive = {}
    for t in turns:
        d = t["target_drive"]
        by_drive.setdefault(d, []).append(t)

    print(f"\nDone. {len(turns)} tagged turns saved to {args.output}")
    for drive, drive_turns in sorted(by_drive.items()):
        avg_len = sum(len(t["assistant_response"]) for t in drive_turns) / len(drive_turns)
        print(f"  {drive:12s}: {len(drive_turns):3d} turns, avg response {avg_len:.0f} chars")


if __name__ == "__main__":
    main()
