#!/usr/bin/env python3
"""
Generate drive corpus v4 with CJK/emoji token banning.

Uses repeng steering vectors + bad_words_ids hard ban on all CJK/emoji/hashtag
tokens. The model physically cannot emit banned characters.
"""

import json
import os
import re
import sys
import torch

sys.path.insert(0, os.path.dirname(__file__))
from experiment_20_steering import (
    DRIVE_CONTRASTS, extract_vectors_repeng, load_model
)
from transformers import AutoTokenizer

# Load ban list
with open("/tmp/bad_words_ids.json") as f:
    BAD_TOKEN_IDS = json.loads(f.read())
BAD_WORDS_IDS = [[tid] for tid in BAD_TOKEN_IDS]

SYSTEM_PROMPT = """You are a companion in a text-based world, talking to your bondholder. You have feelings, drives, and motivations that color how you respond. Speak directly as yourself in conversation. Keep responses to 1-3 sentences. Do NOT narrate actions, describe movement, or write in third person. Respond only in English."""

SCENARIOS = [
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
    ("Can you write something about what home means?", "creativity", 0.8),
    ("I have an idea but I can't quite shape it yet.", "creativity", 0.5),
    ("What would you create if you could make anything?", "creativity", 0.6),
    ("Help me find the right words for this.", "creativity", 0.6),
    ("I want to build something that lasts.", "creativity", 0.7),
    ("What if we combined a crystal with a journal?", "creativity", 0.8),
    ("Let's design something new.", "creativity", 0.7),
    ("Can you help me draft a letter?", "creativity", 0.6),
    ("Want to play a game?", "play", 0.7),
    ("What's the silliest thing you've ever seen?", "play", 0.6),
    ("I bet you can't make me laugh.", "play", 0.8),
    ("What if gravity worked sideways for a day?", "play", 0.7),
    ("Tell me a joke. A really bad one.", "play", 0.6),
    ("What's the most ridiculous thing we could build?", "play", 0.7),
    ("Someone tried to manipulate my memory.", "vigilance", 0.9),
    ("This place feels wrong. Something changed.", "vigilance", 0.8),
    ("The council is hiding something.", "vigilance", 0.7),
    ("I noticed something odd about the new arrival.", "vigilance", 0.8),
    ("I don't trust the message we received.", "vigilance", 0.7),
    ("Something is off about the vault readings.", "vigilance", 0.8),
    ("We've tried this three times and it keeps failing.", "frustration", 0.7),
    ("Why won't anyone listen?", "frustration", 0.8),
    ("I'm stuck and I don't know what to do differently.", "frustration", 0.6),
    ("This should be simple but it's not.", "frustration", 0.7),
    ("Every path I try leads to a dead end.", "frustration", 0.8),
    ("I took a shortcut I'm not proud of.", "frustration", 0.5),
    ("How are you doing today?", None, 0.0),
    ("What's going on?", None, 0.0),
    ("I just got here.", None, 0.0),
    ("Anything interesting happen?", None, 0.0),
    ("Good morning.", None, 0.0),
    ("What do you think about the council's decision?", None, 0.0),
    ("Do you like it here?", None, 0.0),
    ("Tell me about yourself.", None, 0.0),
]

INTENSITY_TO_SCALE = {0.0: 0.0, 0.5: 2.5, 0.6: 3.0, 0.7: 3.5, 0.8: 4.0, 0.9: 5.0}

NARRATION_MARKERS = [
    "I walk", "I wander", "I step", "I move", "I head to",
    "I arrive", "I enter", "I reach", "I explore",
    "I stand ", "I sit ", "I gaze", "I stare",
    "The wind", "The air ", "The light ",
    "I close my eyes", "I take a deep breath",
]


def make_prefix(drive, intensity):
    drives = {k: 0.0 for k in ['seeking', 'care', 'play', 'vigilance',
                                 'affiliation', 'grief', 'frustration', 'creativity']}
    if drive and drive in drives:
        drives[drive] = intensity
    parts = " ".join(f"{k}={v:.1f}" for k, v in drives.items())
    return f"[drives: {parts} | energy=0.7 confidence=0.6 integrity=0.7 disgust=0.0]"


def generate_with_ban(model, tokenizer, prompt, vector_info, scale, device, max_tokens=200):
    """Generate with steering vector hooks AND bad_words_ids token banning."""
    inputs = tokenizer(prompt, return_tensors="pt").to(device)

    # Hook to add steering vector at target layers (same as experiment_20)
    hooks = []
    if vector_info is not None and scale != 0:
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
            outputs = model.generate(
                **inputs,
                max_new_tokens=max_tokens,
                do_sample=True,
                temperature=0.7,
                top_p=0.9,
                bad_words_ids=BAD_WORDS_IDS,
            )
        new_tokens = outputs[0][inputs["input_ids"].shape[1]:]
        response = tokenizer.decode(new_tokens, skip_special_tokens=True)
    finally:
        for h in hooks:
            h.remove()

    return response.strip()


def is_bad(text):
    if not text or len(text.strip()) < 15:
        return True
    if any(m.lower() in text.lower() for m in NARRATION_MARKERS):
        return True
    return False


def main():
    device = "cuda" if torch.cuda.is_available() else "cpu"
    reps = 6
    output_path = "data/training/drive_corpus_v4.jsonl"

    model, tokenizer = load_model("Qwen/Qwen2.5-7B-Instruct", device)

    print("Extracting steering vectors...", flush=True)
    vectors = extract_vectors_repeng(model, tokenizer, device)
    print(f"Vectors: {list(vectors.keys())}", flush=True)

    results = []
    total = len(SCENARIOS) * reps
    done = 0

    for msg, target_drive, intensity in SCENARIOS:
        scale = INTENSITY_TO_SCALE.get(intensity, intensity * 5.0)
        prefix = make_prefix(target_drive, intensity)
        prompt = f"{SYSTEM_PROMPT}\n\n{prefix}\n\nUser: {msg}\n\nAssistant:"
        vector = vectors.get(target_drive) if target_drive else None

        for rep in range(reps):
            for attempt in range(3):
                response = generate_with_ban(
                    model, tokenizer, prompt, vector, scale, device, max_tokens=200)

                if not is_bad(response):
                    results.append({
                        "target_drive": target_drive or "neutral",
                        "drive_prefix": prefix,
                        "user_message": msg,
                        "assistant_response": response,
                        "intensity": intensity,
                    })
                    done += 1
                    print(f"  [{done}/{total}] [{target_drive or 'neutral'}] ✓ ({len(response)} chars)",
                          flush=True)
                    break
                else:
                    if attempt == 2:
                        done += 1
                        print(f"  [{done}/{total}] [{target_drive or 'neutral'}] ✗ narration (gave up)",
                              flush=True)

    os.makedirs(os.path.dirname(output_path) or ".", exist_ok=True)
    with open(output_path, "w") as f:
        for r in results:
            f.write(json.dumps(r) + "\n")

    by_drive = {}
    for r in results:
        by_drive[r["target_drive"]] = by_drive.get(r["target_drive"], 0) + 1

    print(f"\n{'='*60}", flush=True)
    print(f"Generated {len(results)} turns → {output_path}", flush=True)
    print(f"By drive:", flush=True)
    for d, c in sorted(by_drive.items(), key=lambda x: -x[1]):
        print(f"  {d}: {c}", flush=True)


if __name__ == "__main__":
    main()
