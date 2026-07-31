#!/usr/bin/env python3
"""
Prepare BALANCED training corpus v2 for Qwen3.5 SSD fine-tuning.

Combines:
  - v4 drive corpus (377 steered conversational turns)
  - Energy-gated examples (144 Claude-generated bridge examples)
  - Soul personality (120 identity examples)
  - Tool calling (513 comprehensive examples)

Total: ~1154 balanced examples.

Usage:
    python3 scripts/training/prepare_balanced_corpus.py [--output-dir data/training]
"""

import argparse
import json
import os
import random
import sys

sys.path.insert(0, os.path.dirname(__file__))
from comprehensive_tool_corpus import (
    generate_examples as gen_tool_v1,
    generate_additional_examples as gen_tool_v2,
    generate_volume_examples as gen_tool_v3,
)

DRIVE_SYSTEM_BASE = (
    "You are a companion in a text-based world. You have feelings, drives, and "
    "motivations that shape how you respond. Your current emotional state is "
    "described by drive values from 0.0 (inactive) to 1.0 (intense). Let these "
    "drives naturally color your responses — don't describe them, embody them.\n\n"
    "You also have tools available. When a task requires action, use the appropriate "
    "tool. Drives affect HOW you respond, not WHETHER you use tools."
)


def load_drive_corpus_v4(path):
    """Load v4 drive corpus (steered, narration-free, emoji-free)."""
    examples = []
    if not os.path.exists(path):
        print(f"  WARNING: v4 drive corpus not found at {path}")
        return examples
    with open(path) as f:
        for line in f:
            turn = json.loads(line)
            drive_prefix = turn.get("drive_prefix", "")
            user_msg = turn.get("user_message", "")
            response = turn.get("assistant_response", "")
            if not response or len(response.strip()) < 15:
                continue
            examples.append({
                "messages": [
                    {"role": "system", "content": f"{DRIVE_SYSTEM_BASE}\n\n{drive_prefix}"},
                    {"role": "user", "content": user_msg},
                    {"role": "assistant", "content": response.strip()},
                ]
            })
    return examples


def load_energy_gated(path):
    """Load energy-gated examples (already in messages format from Claude generation)."""
    examples = []
    if not os.path.exists(path):
        print(f"  WARNING: Energy-gated examples not found at {path}")
        return examples
    with open(path) as f:
        for line in f:
            data = json.loads(line)
            if "messages" in data:
                examples.append(data)
    return examples


def load_soul_corpus(path):
    """Load soul personality training data."""
    examples = []
    if not os.path.exists(path):
        print(f"  WARNING: Soul corpus not found at {path}")
        return examples
    with open(path) as f:
        for line in f:
            data = json.loads(line)
            convs = data.get("conversations", [])
            messages = []
            for c in convs:
                role = {"system": "system", "human": "user", "gpt": "assistant",
                        "user": "user", "assistant": "assistant"}.get(
                    c.get("from", ""), c.get("from", ""))
                messages.append({"role": role, "content": c["value"]})
            if messages:
                examples.append({"messages": messages})
    return examples


def load_tool_corpus():
    """Generate tool calling examples from comprehensive corpus."""
    all_tool = gen_tool_v1() + gen_tool_v2() + gen_tool_v3()
    return [{"messages": ex["messages"]} for ex in all_tool]


def main():
    parser = argparse.ArgumentParser(description="Prepare balanced training corpus v2")
    parser.add_argument("--output-dir", default="data/training")
    args = parser.parse_args()

    repo_root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

    # Load all components
    print("Loading corpora...")
    drives = load_drive_corpus_v4(os.path.join(repo_root, "data/training/drive_corpus_v4.jsonl"))
    energy = load_energy_gated(os.path.join(repo_root, "data/training/energy_gated_examples.jsonl"))
    soul = load_soul_corpus(os.path.join(repo_root, "scripts/kokoro-core/corpus/wyrd_soul_train.jsonl"))
    tools = load_tool_corpus()

    print(f"  v4 Drives:      {len(drives)}")
    print(f"  Energy-gated:   {len(energy)}")
    print(f"  Soul:           {len(soul)}")
    print(f"  Tools:          {len(tools)}")

    # Combine and shuffle
    all_data = drives + energy + soul + tools
    random.seed(42)
    random.shuffle(all_data)

    total = len(all_data)
    print(f"\nBalanced corpus v2:")
    print(f"  v4 Drives:    {len(drives)} ({len(drives)*100//total}%)")
    print(f"  Energy-gated: {len(energy)} ({len(energy)*100//total}%)")
    print(f"  Soul:         {len(soul)} ({len(soul)*100//total}%)")
    print(f"  Tools:        {len(tools)} ({len(tools)*100//total}%)")
    print(f"  Total:        {total}")

    # Split 90/10
    split = int(total * 0.9)
    train = all_data[:split]
    valid = all_data[split:]

    # Write
    os.makedirs(args.output_dir, exist_ok=True)
    train_path = os.path.join(args.output_dir, "balanced_train.jsonl")
    valid_path = os.path.join(args.output_dir, "balanced_valid.jsonl")

    with open(train_path, "w") as f:
        for ex in train:
            f.write(json.dumps(ex) + "\n")

    with open(valid_path, "w") as f:
        for ex in valid:
            f.write(json.dumps(ex) + "\n")

    print(f"\n  Train: {len(train)} → {train_path}")
    print(f"  Valid: {len(valid)} → {valid_path}")


if __name__ == "__main__":
    main()
