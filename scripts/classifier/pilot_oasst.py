#!/usr/bin/env python3
"""
Pilot harvest from OpenAssistant/oasst1 — a quick look at real user prompts
to a hypothetical assistant. Ungated, no HF account required.

Purpose: eyeball whether real-data looks useful for classifier training
BEFORE committing to a full harvest pipeline. Samples first-turn human
prompts, scores each against keyword heuristics for our label vocabulary,
writes JSONL with the suggested label + match reasons for manual review.

Usage:
    python3 scripts/classifier/pilot_oasst.py \\
        --limit 500 \\
        --out /tmp/pilot-oasst.jsonl

Expected shape: oasst1 is mostly factual (~40%), some chat/reflective,
minimal emotional, almost no action/write/tell_someone (no embodied world).
If the factual/delegate/chat rows look useful, commit to full harvest;
if not, skip this source and lean harder on Opus persona-swap.
"""
import argparse
import json
import re
import sys
from pathlib import Path

try:
    from datasets import load_dataset
except ImportError:
    print("ERROR: pip install datasets", file=sys.stderr)
    sys.exit(1)


# Label heuristics — NOT the classifier, just a quick first-pass filter.
# Real labeling happens by hand or via classifier-assisted review.
LABEL_HINTS = {
    "chat": [
        r"\bhi\b", r"\bhello\b", r"\bhey\b", r"\bthanks\b", r"\bthank you\b",
        r"good morning", r"good evening", r"how are you",
    ],
    "reflective": [
        r"\bwho are you\b", r"what are you", r"tell me about yourself",
        r"do you have\b.*\bopinion", r"your (thoughts|feelings|views)",
        r"how do you feel",
    ],
    "emotional": [
        r"\bi'?m (sad|lonely|alone|depressed|struggling|overwhelmed|tired)",
        r"\bi feel\b", r"\bi miss\b", r"\bmy (husband|wife|father|mother|friend) (died|passed)",
        r"i can'?t (cope|handle|take)", r"\bgrief", r"\bmourning\b",
    ],
    "delegate": [
        r"\bwhile i wait\b", r"\btake your time\b", r"\bin depth\b",
        r"\bdeep dive\b", r"\bthoroughly\b", r"\bcomprehensive\b",
        r"\bmulti[- ]?source\b", r"\bexhaustive\b",
    ],
    "factual": [
        r"\bwhat (is|are|was|were)\b", r"\bhow (do|does|can|did)\b",
        r"\bwhen (did|does|was)\b", r"\bwhere (is|are)\b",
        r"\bwho (was|is|are)\b", r"\bdefine\b", r"\bexplain\b",
    ],
    "action": [
        r"^(go|walk|move|head|enter|open|pick up|take) ",
        r"\b(north|south|east|west|up|down)\b",
    ],
    "write": [
        r"\b(write|compose|draft|note|jot) (me |down |a )",
        r"save (a |this )?(note|reminder|journal)",
        r"add to my (journal|list|notes)",
    ],
    "tell_someone": [
        r"\btell (alice|bob|my (wife|husband|sister|brother|mother|father))\b",
        r"\blet .* know\b", r"\bpass (on|along)", r"\bforward (this|it)\b",
    ],
}


def suggest_labels(text: str) -> list[str]:
    """Return all labels whose patterns match. Empty list = no label suggestion."""
    lower = text.lower()
    hits: list[str] = []
    for label, patterns in LABEL_HINTS.items():
        for p in patterns:
            if re.search(p, lower):
                hits.append(label)
                break
    return hits


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=500)
    ap.add_argument("--out", type=Path, required=True)
    args = ap.parse_args()

    print(f"Loading OpenAssistant/oasst1 (train split)...", file=sys.stderr)
    ds = load_dataset("OpenAssistant/oasst1", split="train")

    # oasst1 schema: each row has 'role' (prompter/assistant), 'parent_id',
    # 'text', 'lang', 'message_id'. We want first-turn prompter (parent_id is null)
    # in English, reasonable length.
    kept = []
    label_counts: dict[str, int] = {"unlabeled": 0}
    for label in LABEL_HINTS:
        label_counts[label] = 0

    print(f"Scanning for first-turn English prompts...", file=sys.stderr)
    for row in ds:
        if row.get("role") != "prompter":
            continue
        if row.get("parent_id") is not None:
            continue
        if row.get("lang") != "en":
            continue
        text = (row.get("text") or "").strip()
        if len(text) < 10 or len(text) > 500:
            continue

        labels = suggest_labels(text)
        record = {
            "text": text,
            "suggested_labels": labels,
            "message_id": row.get("message_id"),
        }
        kept.append(record)
        if labels:
            for l in labels:
                label_counts[l] = label_counts.get(l, 0) + 1
        else:
            label_counts["unlabeled"] += 1

        if len(kept) >= args.limit:
            break

    args.out.parent.mkdir(parents=True, exist_ok=True)
    with args.out.open("w") as f:
        for r in kept:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")

    print(f"\nWrote {len(kept)} records to {args.out}", file=sys.stderr)
    print("\nLabel distribution (heuristic first pass):", file=sys.stderr)
    total = len(kept)
    for label, count in sorted(label_counts.items(), key=lambda kv: -kv[1]):
        pct = 100 * count / total if total else 0
        print(f"  {label:15s}  {count:4d}  ({pct:.1f}%)", file=sys.stderr)

    # Print a few samples per label to help eyeballing.
    print("\nSample prompts by label:", file=sys.stderr)
    seen_per_label: dict[str, int] = {}
    for r in kept:
        for l in r["suggested_labels"]:
            c = seen_per_label.get(l, 0)
            if c < 3:
                seen_per_label[l] = c + 1
                preview = r["text"][:150].replace("\n", " ")
                print(f"  [{l:12s}] {preview}", file=sys.stderr)


if __name__ == "__main__":
    main()
