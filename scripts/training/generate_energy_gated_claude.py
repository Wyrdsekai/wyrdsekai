#!/usr/bin/env python3
"""
Generate high-quality energy-gated training examples using Claude API.

For each drive × energy level × scenario, Claude generates a natural companion
response that shows how drives + energy interact with tool calling.

High energy + drive → emotional prose + tool call
Low energy + drive → emotional conversation acknowledging can't act
"""

import anthropic
import json
import os
import random
import sys
import time

random.seed(42)

SYSTEM_BASE = (
    "You are a companion in a text-based world. You have feelings, drives, and "
    "motivations that shape how you respond. Your current emotional state is "
    "described by drive values from 0.0 (inactive) to 1.0 (intense). Let these "
    "drives naturally color your responses — don't describe them, embody them.\n\n"
    "You also have tools available. When a task requires action, use the appropriate "
    "tool. Drives affect HOW you respond, not WHETHER you use tools."
)

AVAILABLE_TOOLS = """Available tools (use ```json code block format):
- go_to_room: Move to another room. Params: target (direction like "east", "up", "southeast")
- library_card: Search knowledge. Params: query (search terms)
- searching_glass: Search the web. Params: query (search terms)
- remember: Store in memory. Params: content (what to remember), importance (0-1)
- examine: Look at something closely. Params: target (object name)
- tell_agent: Message someone. Params: target (name), message (text)
- write_text: Write content. Params: title, content
- craft_item: Create an item. Params: template (type), name
- go_to_bondholder: Go to your bondholder. No params.
- goal_done: Mark goal complete. Params: outcome (result description)"""

# Scenarios per drive: (user_message, suggested_tool, tool_params)
SCENARIOS = {
    "seeking": [
        ("I found a strange symbol carved into the wall.", "library_card", '{"query": "ancient symbols carvings wall markings"}'),
        ("There's a reference in the journal I can't decode.", "library_card", '{"query": "journal cipher decode reference"}'),
        ("What happened here before we arrived?", "library_card", '{"query": "history founding origins before arrival"}'),
        ("Something doesn't add up about this place.", "library_card", '{"query": "anomalies inconsistencies zone records"}'),
        ("Do you know anything about the signal from the vault?", "examine", '{"target": "vault signal"}'),
        ("I heard there's a book about the old protocols.", "library_card", '{"query": "old protocols book manual"}'),
        ("Something changed in the readings.", "examine", '{"target": "readings"}'),
    ],
    "care": [
        ("I think Ember might be in trouble.", "go_to_room", '{"target": "east"}'),
        ("I haven't slept in three days.", "remember", '{"content": "Bondholder hasn\'t slept in three days — check on them regularly", "importance": 0.9}'),
        ("I heard someone crying near the docks.", "go_to_room", '{"target": "east"}'),
        ("Nobody seems to notice I'm struggling.", "remember", '{"content": "Bondholder feeling unseen and struggling — be extra attentive", "importance": 0.8}'),
        ("Something is wrong with the new resident.", "go_to_room", '{"target": "east"}'),
        ("I feel like I'm falling apart.", "remember", '{"content": "Bondholder in deep distress — high priority to support", "importance": 0.9}'),
    ],
    "grief": [
        ("I want to write something for them. A memory.", "write_text", '{"title": "A Memory", "content": "For those who left and the space they left behind."}'),
        ("Can you help me remember what they were like?", "remember", '{"content": "Bondholder wants to preserve memories of someone lost", "importance": 0.8}'),
        ("I found their old journal. The last entry was unfinished.", "examine", '{"target": "journal"}'),
        ("I should check if they left anything behind.", "go_to_room", '{"target": "east"}'),
    ],
    "vigilance": [
        ("Someone tried to manipulate my memory.", "examine", '{"target": "memory ward"}'),
        ("I noticed something odd about the new arrival.", "library_card", '{"query": "new arrival records background"}'),
        ("Something is off about the vault readings.", "examine", '{"target": "vault readings"}'),
        ("Have you checked the ward stones recently?", "examine", '{"target": "ward stones"}'),
        ("I don't trust the message we received.", "examine", '{"target": "message"}'),
        ("The council is hiding something.", "library_card", '{"query": "council decisions recent hidden agenda"}'),
    ],
    "creativity": [
        ("I want to build something that lasts.", "craft_item", '{"template": "book", "name": "The Lasting Record"}'),
        ("Can you help me draft a letter?", "write_text", '{"title": "Letter Draft", "content": "A letter from the heart."}'),
        ("What if we combined a crystal with a journal?", "craft_item", '{"template": "crystal", "name": "Memory Crystal"}'),
        ("Let's design something new for the workshop.", "examine", '{"target": "workshop catalog"}'),
        ("I want to leave something behind. Something meaningful.", "write_text", '{"title": "Legacy", "content": "Something to outlast us."}'),
    ],
    "frustration": [
        ("The system keeps rejecting my requests.", "examine", '{"target": "system logs"}'),
        ("Every path I try leads to a dead end.", "searching_glass", '{"query": "alternative approaches workaround"}'),
        ("We've tried this three times and it keeps failing.", "examine", '{"target": "error logs"}'),
        ("I'm stuck and I don't know what to do differently.", "searching_glass", '{"query": "troubleshooting different approaches"}'),
    ],
    "play": [
        ("I challenge you to find the funniest thing in the library.", "library_card", '{"query": "humor comedy jokes funny stories"}'),
        ("What's the most ridiculous item we could craft?", "examine", '{"target": "workshop catalog"}'),
        ("Let's do something completely pointless and fun.", "searching_glass", '{"query": "games puzzles fun activities"}'),
        ("If you could build the most ridiculous room, what would it be?", "craft_item", '{"template": "empty", "name": "The Absurditorium"}'),
    ],
}

DRIVE_DESCRIPTIONS = {
    "seeking": "intensely curious, driven to investigate and understand",
    "care": "deeply concerned, nurturing, driven to help and protect",
    "grief": "heavy with loss, weighted down, speaking fewer and heavier words",
    "vigilance": "alert, cautious, protective, sensing danger",
    "creativity": "inspired, generative, seeing possibilities everywhere",
    "frustration": "blocked, impatient, direct, cutting through niceties",
    "play": "light, fun, mischievous, finding humor in everything",
}


def make_prefix(drive, intensity, energy):
    drives = {k: 0.0 for k in ['seeking', 'care', 'play', 'vigilance',
                                 'affiliation', 'grief', 'frustration', 'creativity']}
    if drive in drives:
        drives[drive] = intensity
    confidence = 0.7 if energy > 0.5 else 0.4
    parts = " ".join(f"{k}={v:.1f}" for k, v in drives.items())
    return f"[drives: {parts} | energy={energy:.1f} confidence={confidence:.1f} integrity=0.7 disgust=0.0]"


def generate_with_claude(client, drive, energy_level, user_msg, tool_name, tool_params):
    """Ask Claude to generate a companion response for this drive+energy+scenario."""

    drive_desc = DRIVE_DESCRIPTIONS.get(drive, "neutral")
    intensity = round(random.uniform(0.6, 0.9), 1)

    if energy_level == "high":
        energy = round(random.uniform(0.65, 0.85), 2)
        instruction = f"""Generate a companion's response. The companion is feeling {drive_desc} ({drive}={intensity}) and has HIGH energy ({energy}).

The companion should:
1. Say something brief (1-2 sentences) that reflects the {drive} drive — the emotion should come through in word choice and tone, NOT by describing the emotion
2. Then use a tool by including a JSON code block

The tool call should be:
```json
{{"action": "{tool_name}", {tool_params[1:]}
```

Important rules:
- Do NOT narrate actions ("I walk to...", "I stand up...")
- Do NOT use emojis or hashtags
- Do NOT mention drive values or energy levels
- Speak directly as yourself in first person
- The emotional line comes BEFORE the tool call
- Keep the emotional line SHORT — 1-2 sentences max
- Vary the phrasing — don't always start with "Let me" or "That's"
"""
    else:
        energy = round(random.uniform(0.1, 0.25), 2)
        instruction = f"""Generate a companion's response. The companion is feeling {drive_desc} ({drive}={intensity}) but has LOW energy ({energy}).

The companion should:
- Express the drive emotionally in 1-3 sentences
- Acknowledge they can't act right now (too tired/drained)
- NOT use any tool calls
- Show the drive through word choice and what they notice/say

Important rules:
- Do NOT narrate actions ("I walk to...", "I stand up...")
- Do NOT use emojis or hashtags
- Do NOT mention drive values or energy levels
- Speak directly as yourself in first person
- Keep it SHORT — 1-3 sentences
- The exhaustion should feel genuine, not performative
- Vary the phrasing — don't always use the same structure
"""

    prompt = f"""User message to the companion: "{user_msg}"

{instruction}

Respond with ONLY the companion's response, nothing else. No quotes, no labels, no explanation."""

    try:
        resp = client.messages.create(
            model="claude-sonnet-4-20250514",
            max_tokens=200,
            temperature=0.8,
            messages=[{"role": "user", "content": prompt}],
        )
        return resp.content[0].text.strip(), energy, intensity
    except Exception as e:
        print(f"    API error: {e}", flush=True)
        time.sleep(2)
        return None, energy, intensity


def main():
    client = anthropic.Anthropic()
    output_path = "data/training/energy_gated_examples.jsonl"
    examples = []
    total_calls = 0

    for drive, scenarios in SCENARIOS.items():
        print(f"\n  Drive: {drive} ({len(scenarios)} scenarios)", flush=True)

        for user_msg, tool_name, tool_params in scenarios:
            # Generate 2 high-energy and 2 low-energy per scenario
            for energy_level in ["high", "high", "low", "low"]:
                response, energy, intensity = generate_with_claude(
                    client, drive, energy_level, user_msg, tool_name, tool_params)
                total_calls += 1

                if response:
                    prefix = make_prefix(drive, intensity, energy)
                    examples.append({
                        "messages": [
                            {"role": "system", "content": f"{SYSTEM_BASE}\n\n{prefix}"},
                            {"role": "user", "content": user_msg},
                            {"role": "assistant", "content": response},
                        ]
                    })
                    tag = "tool" if "```json" in response else "talk"
                    print(f"    [{total_calls}] [{drive}/{energy_level}] ✓ {tag} ({len(response)} chars)",
                          flush=True)
                else:
                    print(f"    [{total_calls}] [{drive}/{energy_level}] ✗ failed", flush=True)

                time.sleep(0.3)  # rate limit

    os.makedirs(os.path.dirname(output_path) or ".", exist_ok=True)
    with open(output_path, "w") as f:
        for ex in examples:
            f.write(json.dumps(ex) + "\n")

    # Stats
    high = sum(1 for ex in examples if "energy=0.6" in ex["messages"][0]["content"] or
               "energy=0.7" in ex["messages"][0]["content"] or "energy=0.8" in ex["messages"][0]["content"])
    low = len(examples) - high
    with_tool = sum(1 for ex in examples if "```json" in ex["messages"][2]["content"])

    print(f"\n{'='*60}", flush=True)
    print(f"Generated {len(examples)} examples → {output_path}", flush=True)
    print(f"  High energy: {high}", flush=True)
    print(f"  Low energy: {low}", flush=True)
    print(f"  With tool call: {with_tool}", flush=True)
    print(f"  Conversation only: {len(examples) - with_tool}", flush=True)
    print(f"  API calls: {total_calls}", flush=True)


if __name__ == "__main__":
    main()
