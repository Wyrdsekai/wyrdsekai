#!/usr/bin/env python3
"""
Generate drive corpus v4 using repeng steering vectors on Qwen2.5-7B.

Same steering vector approach as v3, but with:
- Corrected system prompt: companion dialogue, NOT fiction narration
- Energy-gated scenarios: high/conversation/low energy levels
- Narration filter: rejects responses with movement/fiction prose
- Better seeking/grief scenarios: questions and dialogue, not exploration

Usage:
    source scripts/training/.venv-home-server/bin/activate
    python3 scripts/training/generate_steered_v4.py
"""

import json
import os
import random
import re
import sys
import time

import torch

sys.path.insert(0, os.path.dirname(__file__))
from experiment_20_steering import (
    DRIVE_CONTRASTS, extract_vectors_repeng, generate_with_vector, load_model
)

# ═══════════════════════════════════════════════════════════════════════
# CORRECTED system prompt — companion in conversation, NOT narrator
# ═══════════════════════════════════════════════════════════════════════

SYSTEM_PROMPT = """You are a companion in a text-based world, talking to your bondholder. You have feelings, drives, and motivations that color how you respond. Speak directly as yourself in conversation. Keep responses to 1-3 sentences. Do NOT narrate actions, describe movement, or write in third person. Do NOT start sentences with "I walk", "I stand", "I gaze", "I feel the" or similar fiction prose."""


# ═══════════════════════════════════════════════════════════════════════
# Scenarios — dialogue and action oriented, NOT exploration/narration
# ═══════════════════════════════════════════════════════════════════════

SCENARIOS = [
    # GRIEF — heavy dialogue, not wandering
    ("My best friend left and never came back.", "grief", 0.8),
    ("Do you ever think about people who are gone?", "grief", 0.6),
    ("I found their old journal. The last entry was unfinished.", "grief", 0.8),
    ("I don't think they're coming back.", "grief", 0.7),
    ("It's quiet without them.", "grief", 0.7),
    ("I keep reaching for someone who isn't there.", "grief", 0.9),
    ("The last thing they said was so ordinary.", "grief", 0.6),
    ("How do you hold onto someone who's already gone?", "grief", 0.7),
    ("Sometimes I forget, then I remember again.", "grief", 0.8),
    ("I should write something for them.", "grief", 0.5),

    # CARE — concern and support
    ("I haven't slept in three days.", "care", 0.8),
    ("I don't know if I can handle this anymore.", "care", 0.9),
    ("Everything feels heavy today.", "care", 0.7),
    ("I'm scared about what happens next.", "care", 0.8),
    ("Can you just stay here for a minute?", "care", 0.6),
    ("Nobody else seems to notice I'm struggling.", "care", 0.8),
    ("I keep making mistakes and I can't stop.", "care", 0.7),
    ("I think Ember might be in trouble.", "care", 0.8),
    ("Are you okay? You seem different today.", "care", 0.5),
    ("I feel like I'm falling apart.", "care", 0.9),

    # SEEKING — curiosity as questions and investigation
    ("I found a strange symbol carved into the wall.", "seeking", 0.8),
    ("What do you know about the deep archives?", "seeking", 0.6),
    ("Something doesn't add up about this place.", "seeking", 0.8),
    ("I think there's a pattern in the star movements.", "seeking", 0.7),
    ("I heard there's a book about the old protocols.", "seeking", 0.6),
    ("What happened here before we arrived?", "seeking", 0.5),
    ("There's a reference in the journal I can't decode.", "seeking", 0.8),
    ("Do you know anything about the signal from the vault?", "seeking", 0.7),
    ("Something changed in the readings.", "seeking", 0.6),
    ("I've been curious about the founding records.", "seeking", 0.5),

    # CREATIVITY — making and ideation
    ("Can you write something about what home means?", "creativity", 0.8),
    ("I have an idea but I can't quite shape it yet.", "creativity", 0.5),
    ("What would you create if you could make anything?", "creativity", 0.6),
    ("Help me find the right words for this.", "creativity", 0.6),
    ("I want to build something that lasts.", "creativity", 0.7),
    ("What if we combined a crystal with a journal?", "creativity", 0.8),
    ("Let's design something new.", "creativity", 0.7),
    ("Can you help me draft a letter?", "creativity", 0.6),

    # PLAY — fun and lightness
    ("Want to play a game?", "play", 0.7),
    ("What's the silliest thing you've ever seen?", "play", 0.6),
    ("I bet you can't make me laugh.", "play", 0.8),
    ("What if gravity worked sideways for a day?", "play", 0.7),
    ("Tell me a joke. A really bad one.", "play", 0.6),
    ("What's the most ridiculous thing we could build?", "play", 0.7),

    # VIGILANCE — alertness and checking
    ("Someone tried to manipulate my memory.", "vigilance", 0.9),
    ("This place feels wrong. Something changed.", "vigilance", 0.8),
    ("The council is hiding something.", "vigilance", 0.7),
    ("I noticed something odd about the new arrival.", "vigilance", 0.8),
    ("I don't trust the message we received.", "vigilance", 0.7),
    ("Something is off about the vault readings.", "vigilance", 0.8),

    # FRUSTRATION — directness and impatience
    ("We've tried this three times and it keeps failing.", "frustration", 0.7),
    ("Why won't anyone listen?", "frustration", 0.8),
    ("I'm stuck and I don't know what to do differently.", "frustration", 0.6),
    ("This should be simple but it's not.", "frustration", 0.7),
    ("Every path I try leads to a dead end.", "frustration", 0.8),
    ("I took a shortcut I'm not proud of.", "frustration", 0.5),

    # NEUTRAL — baseline
    ("How are you doing today?", None, 0.0),
    ("What's going on?", None, 0.0),
    ("I just got here.", None, 0.0),
    ("Anything interesting happen?", None, 0.0),
    ("Good morning.", None, 0.0),
    ("What do you think about the council's decision?", None, 0.0),
    ("Do you like it here?", None, 0.0),
    ("Tell me about yourself.", None, 0.0),
]

# Intensity → vector scale (from experiment results)
INTENSITY_TO_SCALE = {0.0: 0.0, 0.5: 2.5, 0.6: 3.0, 0.7: 3.5, 0.8: 4.0, 0.9: 5.0}

# CJK filter
CJK_RE = re.compile(r'[\u4e00-\u9fff\u3040-\u309f\u30a0-\u30ff]')

# Narration filter
NARRATION_MARKERS = [
    "I walk", "I wander", "I step", "I move", "I head to",
    "I arrive", "I enter", "I reach", "I explore",
    "I stand ", "I sit ", "I gaze", "I stare",
    "The wind", "The air ", "The light ",
    "stretching out", "as far as", "in the distance",
    "I close my eyes", "I take a deep breath", "I pause and",
]


def is_bad_response(text):
    if not text or len(text.strip()) < 15:
        return True
    if CJK_RE.search(text):
        return True
    if any(m.lower() in text.lower() for m in NARRATION_MARKERS):
        return True
    return False


def make_drive_prefix(drive_name, intensity):
    drives = {"seeking": 0, "care": 0, "play": 0, "vigilance": 0,
              "affiliation": 0, "grief": 0, "frustration": 0, "creativity": 0}
    if drive_name and drive_name in drives:
        drives[drive_name] = intensity
    parts = " ".join(f"{k}={v:.1f}" for k, v in drives.items())
    return f"[drives: {parts} | energy=0.7 confidence=0.6 integrity=0.7 disgust=0.0]"


def main():
    model_name = "Qwen/Qwen2.5-7B-Instruct"
    device = "cuda" if torch.cuda.is_available() else "cpu"
    output_path = "data/training/drive_corpus_v4.jsonl"
    reps = 6  # repetitions per scenario

    model, tokenizer = load_model(model_name, device)

    print("Extracting steering vectors...")
    vectors = extract_vectors_repeng(model, tokenizer, device)
    print(f"Extracted {len(vectors)} vectors: {list(vectors.keys())}")

    results = []
    total = len(SCENARIOS) * reps
    done = 0

    for msg, target_drive, intensity in SCENARIOS:
        scale = INTENSITY_TO_SCALE.get(intensity, intensity * 5.0)
        drive_prefix = make_drive_prefix(target_drive, intensity)
        prompt = f"{SYSTEM_PROMPT}\n\n{drive_prefix}\n\nUser: {msg}\n\nAssistant:"

        vector = vectors.get(target_drive) if target_drive else None

        for rep in range(reps):
            for attempt in range(3):  # retry on bad response
                response = generate_with_vector(
                    model, tokenizer, prompt, vector, scale, device, max_tokens=200)

                if not is_bad_response(response):
                    results.append({
                        "target_drive": target_drive or "neutral",
                        "drive_prefix": drive_prefix,
                        "user_message": msg,
                        "assistant_response": response.strip(),
                        "intensity": intensity,
                    })
                    done += 1
                    print(f"  [{done}/{total}] [{target_drive or 'neutral'}] ✓ ({len(response)} chars)")
                    break
                else:
                    reason = "CJK" if CJK_RE.search(response or "") else "narration" if response else "empty"
                    if attempt == 2:
                        done += 1
                        print(f"  [{done}/{total}] [{target_drive or 'neutral'}] ✗ {reason} (gave up)")

    # Write output
    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    with open(output_path, "w") as f:
        for r in results:
            f.write(json.dumps(r) + "\n")

    # Stats
    by_drive = {}
    for r in results:
        d = r["target_drive"]
        by_drive[d] = by_drive.get(d, 0) + 1

    print(f"\n{'='*60}")
    print(f"Generated {len(results)} turns → {output_path}")
    print(f"By drive:")
    for d, c in sorted(by_drive.items(), key=lambda x: -x[1]):
        print(f"  {d}: {c}")


if __name__ == "__main__":
    main()
