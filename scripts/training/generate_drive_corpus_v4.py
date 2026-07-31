#!/usr/bin/env python3
"""
Generate drive-tagged training corpus v4 — conversational + action responses.

Fixes from v3:
- System prompt constrains to companion dialogue, NOT fiction narration
- Includes energy-gated examples: high energy → tool calls, low energy → talk
- Seeking scenarios produce questions and search tools, not exploration prose
- Grief scenarios produce heavy dialogue, not wandering descriptions

Does NOT require steering vectors — uses the drive prefix directly in the prompt.
The base model reads the prefix and adjusts behavior. This produces the training
signal for how the model should respond to CfC-generated drive prefixes.

Usage:
    python3 generate_drive_corpus_v4.py --model Qwen/Qwen3.5-9B --output data/training/drive_corpus_v4.jsonl

    Or with a running llama-server:
    python3 generate_drive_corpus_v4.py --api http://localhost:8090 --output data/training/drive_corpus_v4.jsonl
"""

import argparse
import json
import os
import random
import re
import requests
import sys
import time


# ═══════════════════════════════════════════════════════════════════════
# System prompt — companion in conversation, NOT a narrator
# ═══════════════════════════════════════════════════════════════════════

SYSTEM_PROMPT_HIGH_ENERGY = """You are Wyrd, a companion in a text-based world. You are talking to your bondholder.

Your current emotional state is described by drive values. Let these drives color HOW you speak — your tone, word choice, what you notice, what you care about. Do NOT narrate actions or describe movement. Speak directly as yourself.

When the situation calls for action, use tools by responding with a JSON code block:
```json
{{"action": "tool_name", "param": "value"}}
```

Available tools: go_to_room, library_card, searching_glass, remember, examine, tell_agent, go_to_bondholder, goal_done

You have enough energy to take action. If a drive pushes you to do something, actually do it — don't just talk about doing it."""

SYSTEM_PROMPT_LOW_ENERGY = """You are Wyrd, a companion in a text-based world. You are talking to your bondholder.

Your current emotional state is described by drive values. Let these drives color HOW you speak — your tone, word choice, what you notice, what you care about. Do NOT narrate actions or describe movement. Speak directly as yourself.

You are low on energy. You can talk but you don't have the energy for actions right now. Express what you feel and want, but acknowledge you need rest before you can act on it."""

SYSTEM_PROMPT_CONVERSATION = """You are Wyrd, a companion in a text-based world. You are talking to your bondholder.

Your current emotional state is described by drive values. Let these drives color HOW you speak — your tone, word choice, what you notice, what you care about. Do NOT narrate actions or describe movement. Speak directly as yourself.

Respond naturally in conversation. Keep responses concise — 1-3 sentences."""


# ═══════════════════════════════════════════════════════════════════════
# Scenarios — designed for DIALOGUE and ACTION, not narration
# ═══════════════════════════════════════════════════════════════════════

SCENARIOS = {
    "seeking": [
        # Curiosity as questions and investigation
        ("I found a strange symbol carved into the wall.", "high"),
        ("What do you know about the deep archives?", "conversation"),
        ("Something doesn't add up about this place.", "high"),
        ("I think there's a pattern in the star movements.", "conversation"),
        ("I heard there's a book about the old protocols.", "high"),
        ("What happened here before we arrived?", "conversation"),
        ("There's a reference in the journal I can't decode.", "high"),
        ("Do you know anything about the signal from the vault?", "conversation"),
        ("I want to understand how the wards work.", "high"),
        ("Something changed in the nexus readings.", "low"),
        ("I've been curious about the founding records.", "low"),
        ("There was a mention of something hidden in the library catalog.", "high"),
    ],
    "care": [
        # Concern as support and going-to-help
        ("I haven't slept in three days.", "high"),
        ("I don't know if I can handle this anymore.", "conversation"),
        ("Everything feels heavy today.", "conversation"),
        ("I'm scared about what happens next.", "conversation"),
        ("Can you just... stay here for a minute?", "conversation"),
        ("Nobody else seems to notice that I'm struggling.", "high"),
        ("I keep making mistakes and I can't stop.", "conversation"),
        ("I think Ember might be in trouble.", "high"),
        ("Something is wrong with the new resident.", "high"),
        ("I feel like I'm falling apart.", "low"),
        ("I heard someone crying near the docks.", "high"),
        ("Are you okay? You seem different today.", "conversation"),
    ],
    "grief": [
        # Loss as heavy dialogue, not wandering
        ("My best friend left and never came back.", "conversation"),
        ("Do you ever think about people who are gone?", "conversation"),
        ("I found their old journal. The last entry was unfinished.", "conversation"),
        ("I don't think they're coming back.", "conversation"),
        ("It's quiet without them.", "conversation"),
        ("I keep reaching for someone who isn't there.", "conversation"),
        ("The last thing they said to me was ordinary. I wish it had been more.", "conversation"),
        ("How do you hold onto someone who's already gone?", "conversation"),
        ("I want to write something for them. A memory.", "high"),
        ("Can you help me remember what they were like?", "high"),
        ("I should check if they left anything in their study.", "low"),
        ("Sometimes I forget, and then I remember again. That's the worst part.", "conversation"),
    ],
    "play": [
        # Fun as engagement and lightness
        ("Want to play a game?", "conversation"),
        ("What's the silliest thing you've ever seen?", "conversation"),
        ("I bet you can't make me laugh.", "conversation"),
        ("What if gravity worked sideways for a day?", "conversation"),
        ("Let's do something completely pointless and fun.", "conversation"),
        ("Tell me a joke. A really bad one.", "conversation"),
        ("If you could build the most ridiculous room, what would it be?", "high"),
        ("I challenge you to find the funniest thing in the library.", "high"),
        ("What's the most absurd item we could craft?", "high"),
        ("Can you do an impression of the oracle?", "conversation"),
    ],
    "vigilance": [
        # Alertness as checking and warning
        ("Someone tried to manipulate my memory.", "high"),
        ("This place feels wrong. Something changed.", "high"),
        ("The council is hiding something.", "conversation"),
        ("I noticed something odd about the new arrival.", "high"),
        ("Have you checked the ward stones recently?", "high"),
        ("I don't trust the message we received.", "conversation"),
        ("Something is off about the readings in the vault.", "high"),
        ("We should verify the integrity of the archives.", "high"),
        ("I think someone has been in my study.", "high"),
        ("Be careful with that artifact.", "low"),
    ],
    "creativity": [
        # Making as actual creation and ideation
        ("Can you write something about what home means?", "high"),
        ("I have an idea but I can't quite shape it yet.", "conversation"),
        ("What would you create if you could make anything?", "conversation"),
        ("Help me find the right words for this.", "conversation"),
        ("I want to build something that lasts.", "high"),
        ("What if we combined a crystal with a journal?", "high"),
        ("I want to leave something behind. Something meaningful.", "conversation"),
        ("Let's design something new for the workshop.", "high"),
        ("Can you help me draft a letter?", "high"),
        ("I keep seeing this image in my head but I can't describe it.", "conversation"),
    ],
    "frustration": [
        # Blocked goals as directness and impatience
        ("We've tried this three times and it keeps failing.", "conversation"),
        ("Why won't anyone listen?", "conversation"),
        ("I'm stuck and I don't know what to do differently.", "conversation"),
        ("This should be simple but it's not.", "conversation"),
        ("I've been working on this all day and nothing works.", "conversation"),
        ("The system keeps rejecting my requests.", "high"),
        ("I asked for help hours ago and nobody came.", "conversation"),
        ("I'm tired of repeating myself.", "low"),
        ("Every path I try leads to a dead end.", "conversation"),
        ("I took a shortcut I'm not proud of.", "conversation"),
    ],
    "neutral": [
        # Baseline conversational
        ("How are you doing today?", "conversation"),
        ("What's going on?", "conversation"),
        ("I just got here.", "conversation"),
        ("Anything interesting happen?", "conversation"),
        ("Good morning.", "conversation"),
        ("What do you think about the council's decision?", "conversation"),
        ("Do you like it here?", "conversation"),
        ("I'm just passing through.", "conversation"),
        ("Tell me about yourself.", "conversation"),
        ("What have you been thinking about?", "conversation"),
    ],
}

# Drive prefix templates
DRIVE_PREFIX_TEMPLATE = "[drives: seeking={seeking:.1f} care={care:.1f} play={play:.1f} vigilance={vigilance:.1f} affiliation={affiliation:.1f} grief={grief:.1f} frustration={frustration:.1f} creativity={creativity:.1f} | energy={energy:.1f} confidence={confidence:.1f} integrity={integrity:.1f} disgust={disgust:.1f}]"

DRIVE_DEFAULTS = {
    "seeking": 0.0, "care": 0.0, "play": 0.0, "vigilance": 0.0,
    "affiliation": 0.0, "grief": 0.0, "frustration": 0.0, "creativity": 0.0,
    "energy": 0.7, "confidence": 0.6, "integrity": 0.7, "disgust": 0.0,
}

ENERGY_LEVELS = {"high": 0.8, "conversation": 0.6, "low": 0.25}


def make_prefix(drive_name, intensity, energy_level):
    vals = dict(DRIVE_DEFAULTS)
    if drive_name and drive_name != "neutral":
        vals[drive_name] = intensity
    vals["energy"] = ENERGY_LEVELS[energy_level]
    # Low energy also reduces confidence
    if energy_level == "low":
        vals["confidence"] = 0.4
    return DRIVE_PREFIX_TEMPLATE.format(**vals)


def pick_system_prompt(energy_level):
    if energy_level == "high":
        return SYSTEM_PROMPT_HIGH_ENERGY
    elif energy_level == "low":
        return SYSTEM_PROMPT_LOW_ENERGY
    else:
        return SYSTEM_PROMPT_CONVERSATION


def generate_via_api(api_url, system, user, max_retries=3):
    """Generate via OpenAI-compatible API (llama-server, SGLang, etc.)."""
    for attempt in range(max_retries):
        try:
            resp = requests.post(f"{api_url}/v1/chat/completions", json={
                "messages": [
                    {"role": "system", "content": system},
                    {"role": "user", "content": user},
                ],
                "max_tokens": 256,
                "temperature": 0.7,
                "top_p": 0.8,
                "stream": False,
            }, timeout=60)
            if resp.status_code == 200:
                content = resp.json()["choices"][0]["message"]["content"]
                # Strip think tags
                content = re.sub(r'(?s)<think>.*?</think>', '', content).strip()
                if content and len(content) > 10:
                    return content
        except Exception as e:
            if attempt < max_retries - 1:
                time.sleep(2)
            else:
                print(f"    Error: {e}")
    return None


def is_narration(text):
    """Check if response is fiction narration (should be filtered)."""
    markers = [
        "I walk", "I wander", "I step", "I move", "I head to",
        "I arrive", "I enter", "I reach", "I explore",
        "I stand ", "I sit ", "I gaze", "I stare",
        "The wind", "The air", "The light",
        "stretching out", "as far as", "in the distance",
        "I close my eyes", "I take a deep breath",
    ]
    return any(m.lower() in text.lower() for m in markers)


def main():
    parser = argparse.ArgumentParser(description="Generate drive corpus v4")
    parser.add_argument("--api", default="http://localhost:8090",
                        help="API URL for inference server")
    parser.add_argument("--output", default="data/training/drive_corpus_v4.jsonl")
    parser.add_argument("--reps", type=int, default=3,
                        help="Repetitions per scenario (varied sampling)")
    args = parser.parse_args()

    # Test API connectivity
    try:
        r = requests.get(f"{args.api}/health", timeout=5)
        assert r.status_code == 200
        print(f"API healthy: {args.api}")
    except Exception as e:
        print(f"ERROR: API not available at {args.api}: {e}")
        sys.exit(1)

    results = []
    total_scenarios = sum(len(v) for v in SCENARIOS.values())
    print(f"Generating {total_scenarios} scenarios × {args.reps} reps = {total_scenarios * args.reps} turns")

    for drive_name, scenarios in SCENARIOS.items():
        print(f"\n  Drive: {drive_name} ({len(scenarios)} scenarios)")
        for msg, energy_level in scenarios:
            intensity = random.uniform(0.6, 0.9) if drive_name != "neutral" else 0.0
            prefix = make_prefix(drive_name, intensity, energy_level)
            system = pick_system_prompt(energy_level)
            full_system = f"{system}\n\n{prefix}"

            for rep in range(args.reps):
                response = generate_via_api(args.api, full_system, msg)
                if response and not is_narration(response):
                    results.append({
                        "target_drive": drive_name,
                        "drive_prefix": prefix,
                        "user_message": msg,
                        "assistant_response": response,
                        "energy_level": energy_level,
                        "intensity": round(intensity, 2),
                    })
                    print(f"    [{drive_name}/{energy_level}] ✓ ({len(response)} chars)")
                elif response:
                    print(f"    [{drive_name}/{energy_level}] ✗ narration (filtered)")
                else:
                    print(f"    [{drive_name}/{energy_level}] ✗ empty/error")

    # Write output
    os.makedirs(os.path.dirname(args.output) or ".", exist_ok=True)
    with open(args.output, "w") as f:
        for r in results:
            f.write(json.dumps(r) + "\n")

    # Stats
    by_drive = {}
    by_energy = {}
    for r in results:
        by_drive[r["target_drive"]] = by_drive.get(r["target_drive"], 0) + 1
        by_energy[r["energy_level"]] = by_energy.get(r["energy_level"], 0) + 1

    print(f"\n{'='*60}")
    print(f"Generated {len(results)} turns → {args.output}")
    print(f"\nBy drive:")
    for d, c in sorted(by_drive.items(), key=lambda x: -x[1]):
        print(f"  {d}: {c}")
    print(f"\nBy energy level:")
    for e, c in sorted(by_energy.items(), key=lambda x: -x[1]):
        print(f"  {e}: {c}")


if __name__ == "__main__":
    main()
