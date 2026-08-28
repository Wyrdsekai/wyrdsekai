#!/usr/bin/env python3
"""
Prepare 9B-SPECIFIC training corpus for Qwen3.5 SSD fine-tuning.

The 9B model has enough capacity to learn competing patterns. With the standard
balanced corpus (32% drives, 44% tools), the 377 conversational drive examples
compete with 513 tool examples, causing the 9B to narrate actions instead of
calling tools (e.g., "I go to the library" instead of go_to_room).

Fix: different ratios for 9B:
  - Fewer drives (subsample to ~180, ~15%)
  - Anti-narration examples (28, teach tool call > narration)
  - Extra remember examples (30, fix the format gap)
  - More tool weight (~55%)
  - Same soul and energy-gated

The 4B corpus (prepare_balanced_corpus.py) stays unchanged — it's 14/14 on Ember tasks.

Usage:
    python3 scripts/training/prepare_9b_corpus.py [--output-dir data/training]
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

TOOL_SYSTEM = (
    "You are a companion in a text-based world with tools available. "
    "When the player asks you to do something, use the appropriate tool call. "
    "NEVER narrate actions like 'I go to...' or 'I examine...' — use tool calls instead. "
    "You can speak AND use tools in the same response."
)


def load_drive_corpus_v4(path, max_per_drive=25):
    """Load v4 drive corpus, subsampled to balance across drives."""
    examples = []
    if not os.path.exists(path):
        print(f"  WARNING: v4 drive corpus not found at {path}")
        return examples

    by_drive = {}
    with open(path) as f:
        for line in f:
            turn = json.loads(line)
            drive_prefix = turn.get("drive_prefix", "")
            user_msg = turn.get("user_message", "")
            response = turn.get("assistant_response", "")
            if not response or len(response.strip()) < 15:
                continue

            # Parse dominant drive from prefix like "[drives: seeking=0.0 care=0.8 ...]"
            drive = "neutral"
            import re
            for m in re.finditer(r'(\w+)=([\d.]+)', drive_prefix):
                name, val = m.group(1), float(m.group(2))
                if name in ("seeking", "care", "play", "vigilance", "grief",
                            "frustration", "creativity") and val >= 0.5:
                    drive = name
                    break

            if drive not in by_drive:
                by_drive[drive] = []
            by_drive[drive].append({
                "messages": [
                    {"role": "system", "content": f"{DRIVE_SYSTEM_BASE}\n\n{drive_prefix}"},
                    {"role": "user", "content": user_msg},
                    {"role": "assistant", "content": response.strip()},
                ]
            })

    for drive, exs in sorted(by_drive.items()):
        random.shuffle(exs)
        examples.extend(exs[:max_per_drive])
        print(f"    Drive '{drive}': {len(exs)} available, kept {min(len(exs), max_per_drive)}")

    return examples


def load_energy_gated(path):
    examples = []
    if not os.path.exists(path):
        return examples
    with open(path) as f:
        for line in f:
            data = json.loads(line)
            if "messages" in data:
                examples.append(data)
    return examples


def load_soul_corpus(path):
    examples = []
    if not os.path.exists(path):
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
    all_tool = gen_tool_v1() + gen_tool_v2() + gen_tool_v3()
    return [{"messages": ex["messages"]} for ex in all_tool]


def generate_anti_narration_examples():
    """Teach tool calls over narration — the 9B's specific weakness."""
    examples = []
    scenarios = [
        ("Go to the library", '```json\n{"action": "go_to_room", "target": "library"}\n```\nOn my way!'),
        ("Head to the boiler room", '```json\n{"action": "go_to_room", "target": "boiler-room"}\n```\nHeading there now.'),
        ("Take me to the docks", '```json\n{"action": "go_to_room", "target": "docks"}\n```\nLet\'s go.'),
        ("Can you go check the forge?", '```json\n{"action": "go_to_room", "target": "the-forge"}\n```\nI\'ll check it out.'),
        ("Go east", '```json\n{"action": "go_to_room", "target": "east"}\n```'),
        ("Move to the nexus", '```json\n{"action": "go_to_room", "target": "nexus"}\n```\nMoving there.'),
        ("Visit the oracle", '```json\n{"action": "go_to_room", "target": "oracle-sanctum"}\n```\nLet me visit the Oracle.'),
        ("Go downstairs", '```json\n{"action": "go_to_room", "target": "down"}\n```'),
        ("Head southeast to the library", '```json\n{"action": "go_to_room", "target": "southeast"}\n```\nOn my way to the library.'),
        ("Navigate to the workshop", '```json\n{"action": "go_to_room", "target": "workshop"}\n```'),
        ("Look at the crystal", '```json\n{"action": "examine", "target": "crystal"}\n```\nLet me take a closer look.'),
        ("Check the pressure gauge", '```json\n{"action": "examine", "target": "pressure gauge"}\n```\nChecking it now.'),
        ("Inspect the anvil", '```json\n{"action": "examine", "target": "anvil"}\n```'),
        ("What does the sigil look like?", '```json\n{"action": "examine", "target": "sigil"}\n```\nLet me examine it carefully.'),
        ("Look at the door", '```json\n{"action": "examine", "target": "door"}\n```'),
        ("Search for books about dragons", '```json\n{"action": "library_card", "query": "dragons"}\n```\nSearching the library...'),
        ("Find information about ancient runes", '```json\n{"action": "library_card", "query": "ancient runes"}\n```\nLet me look that up.'),
        ("Look up the latest news about AI", '```json\n{"action": "searching_glass", "query": "latest AI news", "type": "news"}\n```\nSearching...'),
        ("Tell Chief that the pressure is stable", '```json\n{"action": "tell_agent", "target": "Chief", "message": "The pressure is stable."}\n```'),
        ("Send a message to Ember about the meeting", '```json\n{"action": "tell_agent", "target": "Ember", "message": "I wanted to let you know about the upcoming meeting."}\n```'),
        ("Go to the library and search for mythology", 'I\'ll head to the library and search for you.\n\n```json\n{"action": "go_to_room", "target": "library"}\n```'),
        ("Find Chief and ask about the boiler", 'Let me go find Chief.\n\n```json\n{"action": "go_to_room", "target": "boiler-room"}\n```'),
        ("Remember that my favorite color is blue", '```json\n{"action": "remember", "content": "Player\'s favorite color is blue", "importance": 0.7}\n```\nI\'ll remember that.'),
        ("Remember that the pressure was at 87%", '```json\n{"action": "remember", "content": "Pressure gauge reading: 87%", "importance": 0.8}\n```\nNoted.'),
        ("Keep in mind that I prefer short answers", '```json\n{"action": "remember", "content": "Player prefers concise, short answers", "importance": 0.6}\n```\nUnderstood.'),
        ("Remember Chief said the valves need checking", '```json\n{"action": "remember", "content": "Chief mentioned the valves need checking", "importance": 0.7}\n```\nI\'ll remember that.'),
        ("Note that the east corridor is blocked", '```json\n{"action": "remember", "content": "East corridor is currently blocked", "importance": 0.8}\n```\nGot it.'),
        ("Remember we discussed the soul forge yesterday", '```json\n{"action": "remember", "content": "Previous discussion about the soul forge", "importance": 0.5}\n```\nNoted.'),
    ]
    players = ["Operator", "Alex", "Robin", "Kai", "Ember", "Sage"]
    for user_msg, assistant_resp in scenarios:
        player = random.choice(players)
        examples.append({
            "messages": [
                {"role": "system", "content": TOOL_SYSTEM},
                {"role": "user", "content": f"[from {player}] {user_msg}"},
                {"role": "assistant", "content": assistant_resp},
            ]
        })
    return examples


def generate_extra_remember_examples():
    """Additional remember examples in ```json format."""
    examples = []
    scenarios = [
        ("Remember that the forge is hot today", "The forge temperature is elevated today", 0.5),
        ("Remember my name is Sarah", "Player's name is Sarah", 0.9),
        ("Remember that we need 3 iron ingots", "Need 3 iron ingots for current task", 0.7),
        ("Note that the oracle mentioned a storm", "Oracle predicted an incoming storm", 0.8),
        ("Remember the password is starlight", "Password is 'starlight'", 0.9),
        ("Keep in mind the market opens at dawn", "Market opens at dawn", 0.5),
        ("Remember that Ember likes poetry", "Ember has an interest in poetry", 0.6),
        ("Note that the bridge is damaged", "Bridge is currently damaged and unsafe", 0.8),
        ("Remember I'm allergic to moonberries", "Player is allergic to moonberries", 0.7),
        ("Remember Chief's shift ends at midnight", "Chief's shift ends at midnight", 0.6),
        ("Note the crystal changes color at night", "Crystal exhibits color changes during nighttime", 0.7),
        ("Keep in mind the east gate is locked", "East gate is currently locked", 0.8),
        ("Remember that the old map shows a hidden passage", "Old map indicates a hidden passage exists", 0.7),
        ("Note that Robin asked about the weather", "Robin inquired about weather conditions", 0.4),
        ("Remember the ritual requires three candles", "Ritual components: three candles required", 0.8),
        ("Remember I like to explore at night", "Player prefers nighttime exploration", 0.6),
        ("Note that the library has a restricted section", "Library contains a restricted access section", 0.7),
        ("Remember the guardian said to return at sunset", "Guardian instructed to return at sunset", 0.8),
        ("Note that Kai found a strange artifact", "Kai discovered an unusual artifact", 0.7),
        ("Remember the healing spring is north of here", "Healing spring located to the north", 0.7),
        ("Remember that I completed the first trial", "Player completed the first trial", 0.8),
        ("Note the whispers come from the basement", "Whisper sounds originating from basement area", 0.6),
        ("Remember we agreed to meet at the fountain", "Meeting point agreed: the fountain", 0.7),
        ("Keep track that we have 47 gold coins", "Current gold: 47 coins", 0.5),
        ("Remember the merchant's name is Thorne", "Merchant's name: Thorne", 0.7),
        ("Note that the door requires a silver key", "Door lock requires silver key", 0.8),
        ("Remember I prefer tea over coffee", "Player preference: tea over coffee", 0.4),
        ("Remember the prophecy mentions twin moons", "Prophecy reference: twin moons", 0.7),
        ("Remember the docks smell of salt and tar", "Docks area: salt and tar smell", 0.3),
        ("Note that the passage was built by dwarves", "Passage construction: dwarven origin", 0.6),
    ]
    players = ["Operator", "Alex", "Robin", "Kai", "Ember", "Sage"]
    for user_msg, content, importance in scenarios:
        player = random.choice(players)
        examples.append({
            "messages": [
                {"role": "system", "content": TOOL_SYSTEM},
                {"role": "user", "content": f"[from {player}] {user_msg}"},
                {"role": "assistant", "content": f'```json\n{{"action": "remember", "content": "{content}", "importance": {importance}}}\n```\nI\'ll remember that.'},
            ]
        })
    return examples


def main():
    parser = argparse.ArgumentParser(description="Prepare 9B-specific training corpus")
    parser.add_argument("--output-dir", default="data/training")
    parser.add_argument("--max-per-drive", type=int, default=25,
                        help="Max examples per drive category (default: 25, ~180 total)")
    args = parser.parse_args()

    repo_root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    random.seed(42)

    print("Loading corpora for 9B-specific mix...")
    drives = load_drive_corpus_v4(
        os.path.join(repo_root, "data/training/drive_corpus_v4.jsonl"),
        max_per_drive=args.max_per_drive)
    energy = load_energy_gated(os.path.join(repo_root, "data/training/energy_gated_examples.jsonl"))
    soul = load_soul_corpus(os.path.join(repo_root, "scripts/kokoro-core/corpus/wyrd_soul_train.jsonl"))
    tools = load_tool_corpus()
    anti_narration = generate_anti_narration_examples()
    extra_remember = generate_extra_remember_examples()

    print(f"\n  Drives (subsampled):  {len(drives)}")
    print(f"  Energy-gated:        {len(energy)}")
    print(f"  Soul:                {len(soul)}")
    print(f"  Tools:               {len(tools)}")
    print(f"  Anti-narration:      {len(anti_narration)}")
    print(f"  Extra remember:      {len(extra_remember)}")

    all_data = drives + energy + soul + tools + anti_narration + extra_remember
    random.shuffle(all_data)

    total = len(all_data)
    print(f"\n9B corpus v3:")
    print(f"  Drives:          {len(drives)} ({len(drives)*100//total}%)")
    print(f"  Energy-gated:    {len(energy)} ({len(energy)*100//total}%)")
    print(f"  Soul:            {len(soul)} ({len(soul)*100//total}%)")
    print(f"  Tools:           {len(tools)} ({len(tools)*100//total}%)")
    print(f"  Anti-narration:  {len(anti_narration)} ({len(anti_narration)*100//total}%)")
    print(f"  Extra remember:  {len(extra_remember)} ({len(extra_remember)*100//total}%)")
    print(f"  Total:           {total}")

    split = int(total * 0.9)
    train = all_data[:split]
    valid = all_data[split:]

    os.makedirs(args.output_dir, exist_ok=True)
    train_path = os.path.join(args.output_dir, "balanced_9b_train.jsonl")
    valid_path = os.path.join(args.output_dir, "balanced_9b_valid.jsonl")

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
