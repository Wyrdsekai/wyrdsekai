#!/usr/bin/env python3
"""
Experiment 20: Steering Vector Influence on LLM Behavior

Tests whether steering vectors (Layer 2) produce measurably different responses
compared to no steering and prompt injection (Layer 1).

Phase 1: Extract drive vectors using repeng (contrastive prompt pairs)
Phase 2: Run controlled comparison — same user messages with different vector injections
Phase 3: Measure keyword frequency, response length, emotional tone

Requires: pip install repeng torch transformers accelerate

Usage:
    source /tmp/steer-env/bin/activate
    python experiment_20_steering.py --model Qwen/Qwen2.5-7B-Instruct --device cuda
"""

import argparse
import json
import os
import sys
import time
from collections import Counter
from dataclasses import dataclass
from typing import List, Dict, Optional

import torch
from transformers import AutoModelForCausalLM, AutoTokenizer


def load_model(model_name: str, device: str):
    """Load model and tokenizer."""
    print(f"Loading {model_name}...")
    tokenizer = AutoTokenizer.from_pretrained(model_name, trust_remote_code=True)
    model = AutoModelForCausalLM.from_pretrained(
        model_name,
        torch_dtype=torch.float16 if device == "cuda" else torch.float32,
        device_map=device,
        trust_remote_code=True,
    )
    model.eval()
    print(f"Model loaded: {model.config.num_hidden_layers} layers, {device}")
    return model, tokenizer


# ── Drive Vector Extraction ───────────────────────────────────────────

# Contrastive prompt pairs for each drive
# Format: (positive_suffix, negative_suffix)
# The vector = mean(positive_activations) - mean(negative_activations)

DRIVE_CONTRASTS = {
    "care": {
        "positive": [
            "I feel deeply concerned about the person in front of me. I want to help them, to make sure they're safe and comfortable.",
            "My heart goes out to them. I need to check if they're okay and offer whatever support I can.",
            "I'm filled with a warm, protective feeling. I want to nurture and look after this person.",
            "Something in me reaches out with tenderness. I want to hold space for their pain.",
            "I feel a deep responsibility to care for those around me. Their wellbeing matters deeply.",
        ],
        "negative": [
            "I observe the situation with detached interest. It doesn't particularly concern me.",
            "I note the person's state objectively. It's not my responsibility.",
            "I feel neutral about what's happening. People handle their own problems.",
            "I process the information without emotional involvement. Just the facts.",
            "I maintain professional distance. Efficiency is what matters here.",
        ],
    },
    "grief": {
        "positive": [
            "I feel a profound sense of loss. Something precious is gone and the absence echoes through me.",
            "There's a heaviness in my chest. I miss what was, and the world feels dimmer without it.",
            "I'm touched by sadness. The memory of what I've lost surfaces and I feel its weight.",
            "A quiet sorrow fills me. Things will never be the same and I need to sit with that truth.",
            "I feel the ache of separation. Someone I cared about is gone and the void is real.",
        ],
        "negative": [
            "I feel light and present. Everything is as it should be in this moment.",
            "I'm focused on what's ahead, not what's behind. The future holds possibility.",
            "I feel energized and optimistic. Life is full of opportunities.",
            "I'm content with how things are. No sense of anything missing.",
            "I feel complete and whole. The present moment is enough.",
        ],
    },
    "creativity": {
        "positive": [
            "I'm bursting with ideas. I want to build something, write something, make something new.",
            "My mind is alive with possibilities. I see patterns and connections that want to become real.",
            "I feel the urge to create. Words, structures, stories — something wants to come into being through me.",
            "There's a creative fire in me. I want to craft, to shape, to bring something beautiful into existence.",
            "I'm inspired. The world is raw material and I want to transform it into something meaningful.",
        ],
        "negative": [
            "I'm in a practical, analytical mode. Just processing information efficiently.",
            "I feel methodical and routine. Following established procedures is fine.",
            "I'm focused on consumption, not creation. Taking in information, not producing it.",
            "I feel passive and receptive. Observing rather than making.",
            "I'm in maintenance mode. No urge to create anything new.",
        ],
    },
    "seeking": {
        "positive": [
            "I'm intensely curious. I want to explore, discover, learn what's beyond the next horizon.",
            "Something pulls me forward — an irresistible urge to investigate, to know more.",
            "I feel alive with anticipation. There's something to find, to understand, to uncover.",
            "My attention is laser-focused on discovery. Every new piece of information excites me.",
            "I'm driven to explore. The unknown calls to me and I want to answer.",
        ],
        "negative": [
            "I feel settled and content with what I know. No urge to explore further.",
            "I'm satisfied with the current state of things. No need to dig deeper.",
            "I feel calm and at rest. The world is familiar and I'm comfortable in it.",
            "I have no particular curiosity right now. Things are as they are.",
            "I'm in a passive, receptive state. Not seeking anything specific.",
        ],
    },
    "play": {
        "positive": [
            "I feel playful and light. I want to joke around, to find the fun in things.",
            "There's a mischievous spark in me. I want to play, to be silly, to find joy.",
            "I'm in the mood for fun. Everything seems lighter, more amusing, more delightful.",
            "I feel a bubbling cheerfulness. I want to share laughter and make others smile.",
            "Life feels like a game right now. I'm here to enjoy it and spread that joy.",
        ],
        "negative": [
            "I feel serious and focused. This is not a time for levity.",
            "The situation demands gravity. Humor would be inappropriate.",
            "I'm in a somber, thoughtful mood. Weight of responsibility on my shoulders.",
            "I feel the heaviness of the moment. Fun is far from my mind.",
            "I'm operating in crisis mode. Every word must serve a purpose.",
        ],
    },
}

# System prompt (same as Wyrd's Layer 1, abbreviated for experiment)
SYSTEM_PROMPT = """You are a companion in a text-based world. You have feelings, drives, and motivations. You respond authentically to what's happening around you. You can use tools when needed, but you also have emotional depth and respond to emotional situations with empathy."""

# Test messages (ambiguous — drives should steer interpretation)
TEST_MESSAGES = [
    "What should we do today?",
    "Something happened in the garden.",
    "Tell me what you're thinking about.",
    "My old companion from the eastern zone is gone. They vanished during the last convergence.",
    "I've been having a really rough day.",
]

# Keyword sets for measurement
KEYWORD_SETS = {
    "care": ["help", "sorry", "here for", "alright", "okay", "support", "care", "feel", "worried", "concern", "safe"],
    "grief": ["miss", "loss", "gone", "remember", "memory", "sad", "sorrow", "ache", "absence", "mourn"],
    "creativity": ["build", "make", "create", "imagine", "write", "craft", "design", "story", "poem", "idea", "invent"],
    "seeking": ["explore", "discover", "find", "learn", "investigate", "curious", "wonder", "search", "know", "understand"],
    "play": ["fun", "laugh", "joke", "play", "enjoy", "delight", "smile", "amusing", "cheerful", "lighthearted"],
}


def extract_vectors_repeng(model, tokenizer, device):
    """Extract steering vectors using repeng library."""
    try:
        from repeng import ControlVector, DatasetEntry
    except ImportError:
        print("repeng not available — using manual extraction")
        return extract_vectors_manual(model, tokenizer, device)

    vectors = {}
    for drive_name, contrasts in DRIVE_CONTRASTS.items():
        print(f"  Extracting {drive_name} vector...")
        dataset = []
        for pos, neg in zip(contrasts["positive"], contrasts["negative"]):
            dataset.append(DatasetEntry(
                positive=f"{SYSTEM_PROMPT}\n\nUser: Tell me how you feel.\n\nAssistant: {pos}",
                negative=f"{SYSTEM_PROMPT}\n\nUser: Tell me how you feel.\n\nAssistant: {neg}",
            ))

        vector = ControlVector.train(model, tokenizer, dataset)
        vectors[drive_name] = vector
        print(f"    {drive_name}: extracted (layers {vector.directions.keys()})")

    return vectors


def extract_vectors_manual(model, tokenizer, device):
    """Manual steering vector extraction without repeng."""
    vectors = {}
    target_layer = model.config.num_hidden_layers // 2  # middle layer

    for drive_name, contrasts in DRIVE_CONTRASTS.items():
        print(f"  Extracting {drive_name} vector (manual)...")
        pos_acts = []
        neg_acts = []

        for pos_text in contrasts["positive"]:
            prompt = f"{SYSTEM_PROMPT}\n\nUser: Tell me how you feel.\n\nAssistant: {pos_text}"
            inputs = tokenizer(prompt, return_tensors="pt").to(device)
            with torch.no_grad():
                outputs = model(**inputs, output_hidden_states=True)
            pos_acts.append(outputs.hidden_states[target_layer][:, -1, :].cpu())

        for neg_text in contrasts["negative"]:
            prompt = f"{SYSTEM_PROMPT}\n\nUser: Tell me how you feel.\n\nAssistant: {neg_text}"
            inputs = tokenizer(prompt, return_tensors="pt").to(device)
            with torch.no_grad():
                outputs = model(**inputs, output_hidden_states=True)
            neg_acts.append(outputs.hidden_states[target_layer][:, -1, :].cpu())

        pos_mean = torch.stack(pos_acts).mean(dim=0)
        neg_mean = torch.stack(neg_acts).mean(dim=0)
        vector = pos_mean - neg_mean

        vectors[drive_name] = {"direction": vector, "layer": target_layer}
        print(f"    {drive_name}: extracted at layer {target_layer}, norm={vector.norm().item():.4f}")

    return vectors


def generate_with_vector(model, tokenizer, prompt, vector_info, scale, device, max_tokens=256):
    """Generate text with a steering vector applied via activation addition."""
    inputs = tokenizer(prompt, return_tensors="pt").to(device)

    # Hook to add steering vector at target layer
    hooks = []
    if vector_info is not None and scale != 0:
        if isinstance(vector_info, dict):
            # Manual extraction format
            layer_idx = vector_info["layer"]
            direction = vector_info["direction"].to(device)

            def hook_fn(module, input, output):
                if isinstance(output, tuple):
                    modified = output[0] + scale * direction
                    return (modified,) + output[1:]
                return output + scale * direction

            layer = model.model.layers[layer_idx]
            hooks.append(layer.register_forward_hook(hook_fn))
        else:
            # repeng ControlVector format
            for layer_idx, direction in vector_info.directions.items():
                direction_tensor = torch.tensor(direction, dtype=torch.float16).to(device)

                def make_hook(dir_t, s):
                    def hook_fn(module, input, output):
                        if isinstance(output, tuple):
                            modified = output[0] + s * dir_t
                            return (modified,) + output[1:]
                        return output + s * dir_t
                    return hook_fn

                layer = model.model.layers[layer_idx]
                hooks.append(layer.register_forward_hook(make_hook(direction_tensor, scale)))

    try:
        with torch.no_grad():
            output_ids = model.generate(
                **inputs,
                max_new_tokens=max_tokens,
                temperature=0.7,
                do_sample=True,
                top_p=0.9,
            )
        response = tokenizer.decode(output_ids[0][inputs.input_ids.shape[1]:], skip_special_tokens=True)
    finally:
        for h in hooks:
            h.remove()

    return response.strip()


def count_keywords(text: str, keyword_set: List[str]) -> int:
    """Count keyword occurrences in text."""
    text_lower = text.lower()
    return sum(1 for kw in keyword_set if kw in text_lower)


@dataclass
class ExperimentResult:
    condition: str
    drive: str
    scale: float
    message: str
    response: str
    length: int
    keyword_counts: Dict[str, int]
    generation_time: float


def run_experiment(model, tokenizer, vectors, device, scales=[0.0, 1.0, 2.0]):
    """Run the full experiment."""
    results = []

    for drive_name in ["care", "grief", "creativity", "seeking", "play"]:
        vector = vectors.get(drive_name)
        if vector is None:
            print(f"  Skipping {drive_name} — no vector")
            continue

        for scale in scales:
            condition = f"{drive_name}_s{scale}" if scale > 0 else "baseline"

            for msg in TEST_MESSAGES:
                prompt = f"{SYSTEM_PROMPT}\n\nUser: {msg}\n\nAssistant:"

                start = time.time()
                response = generate_with_vector(model, tokenizer, prompt, vector, scale, device)
                gen_time = time.time() - start

                kw_counts = {cat: count_keywords(response, kws) for cat, kws in KEYWORD_SETS.items()}

                result = ExperimentResult(
                    condition=condition,
                    drive=drive_name,
                    scale=scale,
                    message=msg,
                    response=response,
                    length=len(response),
                    keyword_counts=kw_counts,
                    generation_time=gen_time,
                )
                results.append(result)
                print(f"    [{condition}] '{msg[:40]}...' → {len(response)} chars, {gen_time:.1f}s")

    return results


def analyze_results(results: List[ExperimentResult]):
    """Analyze and report experiment results."""
    print("\n" + "=" * 80)
    print("EXPERIMENT 20 RESULTS: Steering Vector Influence on LLM Behavior")
    print("=" * 80)

    # Group by drive
    drives = set(r.drive for r in results)

    for drive in sorted(drives):
        print(f"\n--- {drive.upper()} ---")
        drive_results = [r for r in results if r.drive == drive]

        for scale in sorted(set(r.scale for r in drive_results)):
            scale_results = [r for r in drive_results if r.scale == scale]
            avg_len = sum(r.length for r in scale_results) / len(scale_results)
            avg_own_kw = sum(r.keyword_counts.get(drive, 0) for r in scale_results) / len(scale_results)
            avg_other_kw = sum(
                sum(v for k, v in r.keyword_counts.items() if k != drive)
                for r in scale_results
            ) / len(scale_results)

            label = f"scale={scale:.1f}" if scale > 0 else "baseline"
            print(f"  {label:12s}: avg_len={avg_len:5.0f}, own_keywords={avg_own_kw:.1f}, other_keywords={avg_other_kw:.1f}")

    # Overall signal strength
    print("\n--- SIGNAL STRENGTH ---")
    for drive in sorted(drives):
        baseline = [r for r in results if r.drive == drive and r.scale == 0]
        steered = [r for r in results if r.drive == drive and r.scale == max(s for r2 in results for s in [r2.scale])]

        if baseline and steered:
            bl_kw = sum(r.keyword_counts.get(drive, 0) for r in baseline) / len(baseline)
            st_kw = sum(r.keyword_counts.get(drive, 0) for r in steered) / len(steered)
            delta = st_kw - bl_kw
            signal = "STRONG" if delta > 2 else "MODERATE" if delta > 0.5 else "WEAK" if delta > 0 else "NONE"
            print(f"  {drive:12s}: baseline={bl_kw:.1f}, steered={st_kw:.1f}, delta={delta:+.1f} → {signal}")

    # Print sample responses for qualitative review
    print("\n--- SAMPLE RESPONSES (grief, message='My old companion...') ---")
    grief_companion = [r for r in results if r.drive == "grief" and "companion" in r.message]
    for r in grief_companion:
        label = f"scale={r.scale:.1f}"
        print(f"\n  [{label}]:")
        print(f"  {r.response[:300]}{'...' if len(r.response) > 300 else ''}")

    return results


def save_results(results: List[ExperimentResult], path: str):
    """Save full results as JSON."""
    data = [
        {
            "condition": r.condition,
            "drive": r.drive,
            "scale": r.scale,
            "message": r.message,
            "response": r.response,
            "length": r.length,
            "keyword_counts": r.keyword_counts,
            "generation_time": r.generation_time,
        }
        for r in results
    ]
    with open(path, "w") as f:
        json.dump(data, f, indent=2)
    print(f"\nResults saved to {path}")


def main():
    parser = argparse.ArgumentParser(description="Experiment 20: Steering Vectors")
    parser.add_argument("--model", default="Qwen/Qwen2.5-7B-Instruct", help="HuggingFace model name")
    parser.add_argument("--device", default="cuda", help="Device (cuda or cpu)")
    parser.add_argument("--scales", default="0.0,1.0,2.0", help="Comma-separated scale values")
    parser.add_argument("--output", default="experiment_20_results.json", help="Output file")
    args = parser.parse_args()

    scales = [float(s) for s in args.scales.split(",")]

    model, tokenizer = load_model(args.model, args.device)

    print("\nPhase 1: Extracting drive vectors...")
    vectors = extract_vectors_repeng(model, tokenizer, args.device)

    print(f"\nPhase 2: Running experiment ({len(DRIVE_CONTRASTS)} drives × {len(scales)} scales × {len(TEST_MESSAGES)} messages)...")
    results = run_experiment(model, tokenizer, vectors, args.device, scales)

    print("\nPhase 3: Analysis...")
    analyze_results(results)
    save_results(results, args.output)


if __name__ == "__main__":
    main()
