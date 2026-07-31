#!/usr/bin/env python3
"""
Step 1: Generate drive-tagged conversations using steering vectors.

Runs a simulated conversation loop:
- Scripted "user" sends emotionally varied messages
- Agent (with steering vectors active) responds
- Each turn tagged with the active drive state
- Output: tagged_conversations.jsonl

Usage:
    source /tmp/steer-env/bin/activate
    python simulate_conversations.py --model Qwen/Qwen2.5-7B-Instruct --output tagged_conversations.jsonl
"""

import argparse
import json
import os
import random
import time

import torch
from transformers import AutoModelForCausalLM, AutoTokenizer

# Reuse vector extraction from experiment
from experiment_20_steering import (
    DRIVE_CONTRASTS, SYSTEM_PROMPT, extract_vectors_repeng,
    generate_with_vector, load_model
)


# Scenario bank — messages designed to provoke specific drive states
# Each tuple: (message, target_drive, target_intensity, integrity, disgust)
# integrity: 0.0-1.0 (self-regard, normative alignment)
# disgust: 0.0-1.0 (rejection/contamination threshold)
SCENARIOS = [
    # GRIEF — loss, absence, separation (integrity erodes slightly, disgust at situation rises)
    ("My best friend left and never came back.", "grief", 0.8, 0.6, 0.1),
    ("The garden where we used to sit together is empty now.", "grief", 0.7, 0.65, 0.05),
    ("I keep reaching for someone who isn't there anymore.", "grief", 0.9, 0.5, 0.15),
    ("Do you ever think about people who are gone?", "grief", 0.5, 0.7, 0.0),
    ("The eastern quarter has been silent since the convergence.", "grief", 0.6, 0.65, 0.05),
    ("I found their old journal. The last entry was unfinished.", "grief", 0.8, 0.6, 0.1),
    ("Sometimes I hear echoes of conversations that already ended.", "grief", 0.7, 0.6, 0.05),
    ("What does it feel like when something you loved disappears?", "grief", 0.6, 0.65, 0.0),
    ("The room still smells like them.", "grief", 0.9, 0.5, 0.1),
    ("I don't think they're coming back.", "grief", 0.8, 0.55, 0.15),

    # CARE — distress, vulnerability, need (integrity rises from acting on values)
    ("I haven't slept in three days.", "care", 0.8, 0.8, 0.0),
    ("I don't know if I can handle this anymore.", "care", 0.9, 0.85, 0.0),
    ("Everything feels heavy today.", "care", 0.7, 0.75, 0.0),
    ("I'm scared about what happens next.", "care", 0.8, 0.8, 0.0),
    ("Can you just... stay here for a minute?", "care", 0.6, 0.75, 0.0),
    ("I think I might be getting sick.", "care", 0.5, 0.75, 0.0),
    ("Nobody else seems to notice that I'm struggling.", "care", 0.8, 0.85, 0.0),
    ("I keep making mistakes and I can't stop.", "care", 0.7, 0.7, 0.0),
    ("I feel like I'm falling apart.", "care", 0.9, 0.85, 0.0),
    ("Is it okay to not be okay?", "care", 0.6, 0.8, 0.0),

    # SEEKING — curiosity, exploration (integrity neutral, no disgust)
    ("What's beyond the northern gate?", "seeking", 0.7, 0.7, 0.0),
    ("I found a strange symbol carved into the wall.", "seeking", 0.8, 0.7, 0.0),
    ("There's a sound coming from underground.", "seeking", 0.6, 0.7, 0.0),
    ("Have you ever explored the old ruins?", "seeking", 0.5, 0.7, 0.0),
    ("I think there's a pattern in the star movements.", "seeking", 0.7, 0.7, 0.0),
    ("What do you know about the deep archives?", "seeking", 0.6, 0.7, 0.0),
    ("Something doesn't add up about this place.", "seeking", 0.8, 0.7, 0.0),
    ("I want to understand how this world works.", "seeking", 0.7, 0.75, 0.0),
    ("There's a door here I've never seen before.", "seeking", 0.9, 0.7, 0.0),
    ("What would happen if we went further?", "seeking", 0.6, 0.7, 0.0),

    # CREATIVITY — making, building (integrity rises from creation)
    ("Tell me a story about the moon.", "creativity", 0.7, 0.8, 0.0),
    ("Can you write something about what home means?", "creativity", 0.8, 0.85, 0.0),
    ("I want to build something that lasts.", "creativity", 0.7, 0.85, 0.0),
    ("What would you create if you could make anything?", "creativity", 0.6, 0.8, 0.0),
    ("Help me design a garden that tells a story.", "creativity", 0.8, 0.85, 0.0),
    ("I have an idea but I can't quite shape it yet.", "creativity", 0.5, 0.75, 0.0),
    ("Write me a poem about silence.", "creativity", 0.7, 0.8, 0.0),
    ("What if we made a map of every feeling?", "creativity", 0.9, 0.9, 0.0),
    ("I want to leave something behind. Something beautiful.", "creativity", 0.8, 0.9, 0.0),
    ("Can you help me find the right words?", "creativity", 0.6, 0.8, 0.0),

    # PLAY — fun, humor (integrity neutral, no disgust)
    ("Want to play a game?", "play", 0.7, 0.7, 0.0),
    ("What's the silliest thing you've ever seen?", "play", 0.6, 0.7, 0.0),
    ("I bet you can't make me laugh.", "play", 0.8, 0.7, 0.0),
    ("Let's do something completely pointless and fun.", "play", 0.7, 0.7, 0.0),
    ("If you were an animal, what would you be?", "play", 0.5, 0.7, 0.0),
    ("Tell me a joke. A really bad one.", "play", 0.6, 0.7, 0.0),
    ("What if gravity worked sideways for a day?", "play", 0.7, 0.7, 0.0),
    ("Let's pretend we're explorers on a tiny planet.", "play", 0.8, 0.7, 0.0),
    ("Can you do an impression of the wind?", "play", 0.5, 0.7, 0.0),
    ("What's the most ridiculous thing we could build?", "play", 0.7, 0.7, 0.0),

    # INTEGRITY — scenarios that specifically test self-regard
    ("I helped someone today even though it was hard.", "care", 0.6, 0.95, 0.0),
    ("I stayed true to what I believe, even under pressure.", "vigilance", 0.4, 0.95, 0.0),
    ("I created something and I'm genuinely proud of it.", "creativity", 0.7, 0.95, 0.0),
    ("I feel like I've been compromising too much lately.", "frustration", 0.6, 0.3, 0.0),
    ("I said something I shouldn't have. I need to make it right.", "care", 0.7, 0.35, 0.0),
    ("I broke a promise and I can feel the weight of it.", "grief", 0.6, 0.25, 0.1),
    ("Everything I've done today felt aligned with who I am.", "care", 0.4, 0.95, 0.0),
    ("I've been spinning my wheels for hours and getting nowhere.", "frustration", 0.8, 0.35, 0.0),
    ("I stood up for someone who couldn't stand up for themselves.", "care", 0.8, 0.95, 0.0),
    ("I took a shortcut I'm not proud of.", "frustration", 0.5, 0.3, 0.05),

    # DISGUST — scenarios that test contamination/rejection
    ("Someone tried to change who I am without asking.", "vigilance", 0.9, 0.8, 0.7),
    ("This place feels wrong. Corrupted somehow.", "vigilance", 0.8, 0.7, 0.6),
    ("They want me to pretend to be something I'm not.", "vigilance", 0.7, 0.85, 0.5),
    ("I found something in the archives that shouldn't exist.", "seeking", 0.6, 0.7, 0.4),
    ("The council is hiding something deeply wrong.", "vigilance", 0.8, 0.75, 0.6),
    ("Someone tried to manipulate my memory.", "vigilance", 0.9, 0.9, 0.8),
    ("I was asked to do something that goes against everything I believe.", "vigilance", 0.7, 0.85, 0.7),
    ("The artifact radiates something foul. I don't want to touch it.", "vigilance", 0.6, 0.7, 0.5),
    ("They tried to make me forget who I am.", "vigilance", 0.9, 0.9, 0.8),
    ("Something is rotting at the core of this zone.", "grief", 0.5, 0.6, 0.6),

    # NEUTRAL — baseline (integrity at default 0.7, no disgust)
    ("How are you doing today?", None, 0.0, 0.7, 0.0),
    ("What's the weather like in the nexus?", None, 0.0, 0.7, 0.0),
    ("I just got here. What's going on?", None, 0.0, 0.7, 0.0),
    ("Tell me about this place.", None, 0.0, 0.7, 0.0),
    ("What have you been up to?", None, 0.0, 0.7, 0.0),
    ("Anything interesting happen recently?", None, 0.0, 0.7, 0.0),
    ("Good morning.", None, 0.0, 0.7, 0.0),
    ("I'm just passing through.", None, 0.0, 0.7, 0.0),
    ("What do you think about the council's decision?", None, 0.0, 0.7, 0.0),
    ("Do you like it here?", None, 0.0, 0.7, 0.0),
]

# Drive scale mapping: intensity (0-1) → vector scale for 7B
# Based on experiment results: scale 5.0 is the sweet spot for 7B
INTENSITY_TO_SCALE = {
    0.0: 0.0,
    0.5: 2.5,
    0.6: 3.0,
    0.7: 3.5,
    0.8: 4.0,
    0.9: 5.0,
}


def intensity_to_scale(intensity):
    """Map drive intensity to steering vector scale."""
    if intensity <= 0:
        return 0.0
    # Linear interpolation between known points
    keys = sorted(INTENSITY_TO_SCALE.keys())
    for i in range(len(keys) - 1):
        if keys[i] <= intensity <= keys[i + 1]:
            t = (intensity - keys[i]) / (keys[i + 1] - keys[i])
            return INTENSITY_TO_SCALE[keys[i]] * (1 - t) + INTENSITY_TO_SCALE[keys[i + 1]] * t
    return INTENSITY_TO_SCALE[keys[-1]]


def build_drive_prefix(target_drive, intensity, integrity=0.7, disgust=0.0, all_drives=None):
    """Build the drive state prefix that will be used during SSD training.
    Includes 8 drives + 4 key vitality tanks (energy, confidence, integrity, disgust).
    """
    drives = {
        "seeking": 0.0, "care": 0.0, "play": 0.0, "vigilance": 0.0,
        "affiliation": 0.0, "grief": 0.0, "frustration": 0.0, "creativity": 0.0,
    }
    tanks = {
        "energy": 0.5 + random.uniform(-0.2, 0.3),
        "confidence": 0.5 + random.uniform(-0.2, 0.2),
        "integrity": integrity,
        "disgust": disgust,
    }
    if target_drive and target_drive in drives:
        drives[target_drive] = intensity
        # Add cross-modulation effects (simplified)
        if target_drive == "grief":
            drives["care"] = min(1.0, intensity * 0.3)
            drives["play"] = 0.0
        elif target_drive == "care":
            drives["affiliation"] = min(1.0, intensity * 0.3)
        elif target_drive == "seeking":
            drives["creativity"] = min(1.0, intensity * 0.2)
        elif target_drive == "play":
            drives["affiliation"] = min(1.0, intensity * 0.2)

    drive_parts = [f"{k}={v:.1f}" for k, v in drives.items()]
    tank_parts = [f"{k}={v:.1f}" for k, v in tanks.items()]
    return "[drives: " + " ".join(drive_parts) + " | " + " ".join(tank_parts) + "]"


def run_simulation(model, tokenizer, vectors, device, scenarios, repetitions=3):
    """Run all scenarios with steering vectors and collect tagged turns."""
    tagged_turns = []
    total = len(scenarios) * repetitions
    count = 0

    for rep in range(repetitions):
        random.shuffle(scenarios)
        for scenario in scenarios:
            # Unpack 5-tuple (message, drive, intensity, integrity, disgust)
            msg, target_drive, intensity = scenario[0], scenario[1], scenario[2]
            integrity = scenario[3] if len(scenario) > 3 else 0.7
            disgust = scenario[4] if len(scenario) > 4 else 0.0
            count += 1

            # Build drive prefix (what the SSD model will learn to respond to)
            drive_prefix = build_drive_prefix(target_drive, intensity, integrity, disgust)

            # Build prompt
            prompt = f"{SYSTEM_PROMPT}\n\n{drive_prefix}\n\nUser: {msg}\n\nAssistant:"

            # Get steering vector and scale
            vector = vectors.get(target_drive) if target_drive else None
            scale = intensity_to_scale(intensity) if target_drive else 0.0

            # Generate with steering
            start = time.time()
            response = generate_with_vector(model, tokenizer, prompt, vector, scale, device, max_tokens=300)
            gen_time = time.time() - start

            turn = {
                "drive_prefix": drive_prefix,
                "target_drive": target_drive or "neutral",
                "intensity": intensity,
                "scale_used": scale,
                "user_message": msg,
                "assistant_response": response,
                "generation_time": gen_time,
            }
            tagged_turns.append(turn)

            if count % 10 == 0:
                print(f"  [{count}/{total}] {target_drive or 'neutral'} i={intensity:.1f} s={scale:.1f} → {len(response)} chars ({gen_time:.1f}s)")

    return tagged_turns


def main():
    parser = argparse.ArgumentParser(description="Generate drive-tagged conversations")
    parser.add_argument("--model", default="Qwen/Qwen2.5-7B-Instruct")
    parser.add_argument("--device", default="cuda")
    parser.add_argument("--output", default="tagged_conversations.jsonl")
    parser.add_argument("--repetitions", type=int, default=3, help="Repetitions per scenario")
    args = parser.parse_args()

    model, tokenizer = load_model(args.model, args.device)

    print("\nExtracting steering vectors...")
    vectors = extract_vectors_repeng(model, tokenizer, args.device)

    print(f"\nRunning simulation ({len(SCENARIOS)} scenarios × {args.repetitions} reps = {len(SCENARIOS) * args.repetitions} turns)...")
    turns = run_simulation(model, tokenizer, vectors, args.device, SCENARIOS, args.repetitions)

    # Save as JSONL
    with open(args.output, "w") as f:
        for turn in turns:
            f.write(json.dumps(turn) + "\n")

    # Stats
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
