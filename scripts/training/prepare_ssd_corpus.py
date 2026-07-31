#!/usr/bin/env python3
"""
Step 2: Convert tagged conversations into SSD training format.

Takes the output of simulate_conversations.py and formats it for SSD fine-tuning.
The format includes the drive prefix as part of the system message, teaching the
model to condition its behavior on drive state values.

Output: ssd_corpus.jsonl — each line is a chat-format training example.

Usage:
    python prepare_ssd_corpus.py --input tagged_conversations.jsonl --output ssd_corpus.jsonl
"""

import argparse
import json


SYSTEM_BASE = """You are a companion in a text-based world. You have feelings, drives, and motivations that shape how you respond. Your current emotional state is described by drive values from 0.0 (inactive) to 1.0 (intense). Let these drives naturally color your responses — don't describe them, embody them."""


def format_turn(turn):
    """Convert a tagged turn into SSD chat format."""
    drive_prefix = turn["drive_prefix"]
    user_msg = turn["user_message"]
    response = turn["assistant_response"]

    # Skip empty or very short responses (generation failures)
    if not response or len(response.strip()) < 20:
        return None

    # Skip gibberish (oversteered responses)
    if response.count(response[:20]) > 3:  # repetition detection
        return None

    return {
        "messages": [
            {"role": "system", "content": f"{SYSTEM_BASE}\n\n{drive_prefix}"},
            {"role": "user", "content": user_msg},
            {"role": "assistant", "content": response.strip()},
        ]
    }


def main():
    parser = argparse.ArgumentParser(description="Prepare SSD training corpus")
    parser.add_argument("--input", default="tagged_conversations.jsonl")
    parser.add_argument("--output", default="ssd_corpus.jsonl")
    args = parser.parse_args()

    turns = []
    with open(args.input) as f:
        for line in f:
            turns.append(json.loads(line))

    formatted = []
    skipped = 0
    for turn in turns:
        example = format_turn(turn)
        if example:
            formatted.append(example)
        else:
            skipped += 1

    with open(args.output, "w") as f:
        for ex in formatted:
            f.write(json.dumps(ex) + "\n")

    # Also create train/valid split (90/10)
    split_idx = int(len(formatted) * 0.9)
    train = formatted[:split_idx]
    valid = formatted[split_idx:]

    train_path = args.output.replace(".jsonl", "_train.jsonl")
    valid_path = args.output.replace(".jsonl", "_valid.jsonl")

    with open(train_path, "w") as f:
        for ex in train:
            f.write(json.dumps(ex) + "\n")

    with open(valid_path, "w") as f:
        for ex in valid:
            f.write(json.dumps(ex) + "\n")

    by_drive = {}
    for t in turns:
        d = t["target_drive"]
        by_drive[d] = by_drive.get(d, 0) + 1

    print(f"Prepared {len(formatted)} training examples ({skipped} skipped)")
    print(f"  Train: {len(train)} → {train_path}")
    print(f"  Valid: {len(valid)} → {valid_path}")
    print(f"  By drive: {dict(sorted(by_drive.items()))}")


if __name__ == "__main__":
    main()
