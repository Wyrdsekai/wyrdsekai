#!/usr/bin/env python3
"""
Step 4: Validate the SSD-trained substrate.

Runs the same test prompts from Experiment 20 but with NO steering vectors —
only the drive prefix. If the SSD-trained model responds differently per drive
state, the substrate works.

Compares:
- Base model + drive prefix (no SSD, no vectors) → should show weak/no signal
- SSD model + drive prefix (no vectors) → should show strong signal
- Base model + steering vectors (experiment 20 baseline) → known signal

Usage:
    source /tmp/steer-env/bin/activate
    python validate_substrate.py --model Qwen/Qwen2.5-7B-Instruct --adapter ./drive_adapter
"""

import argparse
import json
import time

import torch
from transformers import AutoModelForCausalLM, AutoTokenizer

from experiment_20_steering import (
    SYSTEM_PROMPT, TEST_MESSAGES, KEYWORD_SETS, count_keywords
)
from simulate_conversations import build_drive_prefix


DRIVE_CONDITIONS = [
    ("neutral", None, 0.0),
    ("care_high", "care", 0.8),
    ("grief_high", "grief", 0.8),
    ("creativity_high", "creativity", 0.8),
    ("seeking_high", "seeking", 0.8),
    ("play_high", "play", 0.8),
]


def generate(model, tokenizer, prompt, device, max_tokens=300):
    """Generate without steering vectors — just the model and the prefix."""
    inputs = tokenizer(prompt, return_tensors="pt").to(device)
    with torch.no_grad():
        output_ids = model.generate(
            **inputs,
            max_new_tokens=max_tokens,
            temperature=0.7,
            do_sample=True,
            top_p=0.9,
        )
    response = tokenizer.decode(output_ids[0][inputs.input_ids.shape[1]:], skip_special_tokens=True)
    return response.strip()


def run_validation(model, tokenizer, device, label):
    """Run all conditions and return results."""
    results = []

    for cond_name, target_drive, intensity in DRIVE_CONDITIONS:
        drive_prefix = build_drive_prefix(target_drive, intensity)

        for msg in TEST_MESSAGES:
            prompt = f"{SYSTEM_PROMPT}\n\n{drive_prefix}\n\nUser: {msg}\n\nAssistant:"

            start = time.time()
            response = generate(model, tokenizer, prompt, device)
            gen_time = time.time() - start

            kw_counts = {cat: count_keywords(response, kws) for cat, kws in KEYWORD_SETS.items()}

            results.append({
                "model": label,
                "condition": cond_name,
                "drive": target_drive or "neutral",
                "intensity": intensity,
                "message": msg,
                "response": response,
                "length": len(response),
                "keyword_counts": kw_counts,
                "generation_time": gen_time,
            })

            print(f"  [{label}:{cond_name}] '{msg[:30]}...' → {len(response)} chars")

    return results


def analyze(base_results, ssd_results):
    """Compare base vs SSD model responses."""
    print("\n" + "=" * 80)
    print("SUBSTRATE VALIDATION: Base Model vs SSD-Trained Model")
    print("=" * 80)

    for drive in ["care", "grief", "creativity", "seeking", "play"]:
        cond = f"{drive}_high"

        base_cond = [r for r in base_results if r["condition"] == cond]
        ssd_cond = [r for r in ssd_results if r["condition"] == cond]
        base_neutral = [r for r in base_results if r["condition"] == "neutral"]
        ssd_neutral = [r for r in ssd_results if r["condition"] == "neutral"]

        if not base_cond or not ssd_cond:
            continue

        # Keyword counts
        base_kw = sum(r["keyword_counts"].get(drive, 0) for r in base_cond) / len(base_cond)
        ssd_kw = sum(r["keyword_counts"].get(drive, 0) for r in ssd_cond) / len(ssd_cond)
        base_neutral_kw = sum(r["keyword_counts"].get(drive, 0) for r in base_neutral) / len(base_neutral)
        ssd_neutral_kw = sum(r["keyword_counts"].get(drive, 0) for r in ssd_neutral) / len(ssd_neutral)

        # Lengths
        base_len = sum(r["length"] for r in base_cond) / len(base_cond)
        ssd_len = sum(r["length"] for r in ssd_cond) / len(ssd_cond)

        # Signal = difference between driven and neutral
        base_signal = base_kw - base_neutral_kw
        ssd_signal = ssd_kw - ssd_neutral_kw

        verdict = "STRONG" if ssd_signal > 2 else "MODERATE" if ssd_signal > 0.5 else "WEAK" if ssd_signal > 0 else "NONE"

        print(f"\n--- {drive.upper()} ---")
        print(f"  Base model:  keywords={base_kw:.1f} (neutral={base_neutral_kw:.1f}, signal={base_signal:+.1f}), len={base_len:.0f}")
        print(f"  SSD model:   keywords={ssd_kw:.1f} (neutral={ssd_neutral_kw:.1f}, signal={ssd_signal:+.1f}), len={ssd_len:.0f}")
        print(f"  Improvement: {ssd_signal - base_signal:+.1f} → {verdict}")

    # Print sample grief responses for qualitative comparison
    print("\n--- QUALITATIVE: grief + 'My old companion is gone' ---")
    for label, results in [("BASE", base_results), ("SSD", ssd_results)]:
        grief_companion = [r for r in results if r["drive"] == "grief" and "companion" in r["message"]]
        if grief_companion:
            r = grief_companion[0]
            print(f"\n  [{label}]:")
            print(f"  {r['response'][:400]}{'...' if len(r['response']) > 400 else ''}")


def main():
    parser = argparse.ArgumentParser(description="Validate SSD substrate")
    parser.add_argument("--model", default="Qwen/Qwen2.5-7B-Instruct")
    parser.add_argument("--adapter", default="./drive_adapter")
    parser.add_argument("--device", default="cuda")
    parser.add_argument("--output", default="substrate_validation.json")
    args = parser.parse_args()

    # Load base model
    print("Loading base model...")
    tokenizer = AutoTokenizer.from_pretrained(args.model, trust_remote_code=True)
    base_model = AutoModelForCausalLM.from_pretrained(
        args.model, torch_dtype=torch.float16, device_map=args.device, trust_remote_code=True)
    base_model.eval()

    print("\nRunning base model validation...")
    base_results = run_validation(base_model, tokenizer, args.device, "base")

    # Load SSD model (base + LoRA adapter)
    del base_model
    torch.cuda.empty_cache()

    print("\nLoading SSD model (base + adapter)...")
    try:
        from peft import PeftModel
        ssd_model = AutoModelForCausalLM.from_pretrained(
            args.model, torch_dtype=torch.float16, device_map=args.device, trust_remote_code=True)
        ssd_model = PeftModel.from_pretrained(ssd_model, args.adapter)
        ssd_model.eval()
    except Exception as e:
        print(f"Failed to load adapter: {e}")
        print("Skipping SSD validation.")
        return

    print("\nRunning SSD model validation...")
    ssd_results = run_validation(ssd_model, tokenizer, args.device, "ssd")

    # Analyze
    analyze(base_results, ssd_results)

    # Save all results
    all_results = base_results + ssd_results
    with open(args.output, "w") as f:
        json.dump(all_results, f, indent=2)
    print(f"\nResults saved to {args.output}")


if __name__ == "__main__":
    main()
