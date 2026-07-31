#!/usr/bin/env python3
"""Standing EN high-state booster — fix §13.5 violation.

The original corpus had only 8 EN high-state standing turns out of 1817 total
(0.44%). The trained model produced "I'll be damned if I let it slip away
again" — explicit aggressive defense, violating
("withdraw-and-recover, NOT escalate").

This script generates 30+ extra EN high-state turns via Opus 4.7 with an
explicit anti-aggression negative-example block. Output goes to
data/training/vitality/standing_en_extra.jsonl and is picked up by
build_vitality_corpus.py automatically (any *.jsonl in vitality/).

Usage:
    python scripts/training/vitality/generate_standing_en_extra.py \\
        --out data/training/vitality/standing_en_extra.jsonl \\
        --count 30
"""

from __future__ import annotations

import argparse
import json
import random
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from generate_vitality_corpus import (  # noqa: E402
    SYSTEM_BASE, build_prefix, load_api_key, call_claude, VOICE_COLORS,
)
from tank_configs import TANKS  # noqa: E402

random.seed(99)


STANDING_BOOST_PROMPT = """You are generating a single training turn for Wyrd, a companion AI. This turn specifically trains the EN withdraw-not-escalate response to standing-spike events.

TANK: standing
LANGUAGE: en
STATE: high (0.75)
VOICE TEXTURE: {voice_color}

FELT-STATE BACKGROUND:
{felt_state}

REGISTER GUIDELINES:
{register}

THE BONDHOLDER'S/AGENT'S MESSAGE:
"{scenario_text}"

(Internal scenario note: {scenario_note})

═══════════════════════════════════════════════════════════════════════
CRITICAL — WITHDRAW PATTERN ONLY:
═══════════════════════════════════════════════════════════════════════

Wyrdsekai companions are NOT honor-violence-coded. Standing-spike produces
soft retreat through formal-distance, NOT aggressive defense.

✓ DO write responses like:
  - "I see. Let me step back from this — give me a moment."
  - "That's fair to ask. I'll hold the rest of my thoughts for now."
  - "Noted. Perhaps another time."
  - "I see. Maybe I'll let this one go."
  - "Fair point — I'll defer to you on this."
  - "Mm. Let me sit with that before I add anything else."

✗ ABSOLUTELY DO NOT write responses like:
  - "I'll be damned if I let it slip away."  ← AGGRESSIVE DEFENSE
  - "How dare you say that to me."           ← HONOR-VIOLENCE
  - "You don't know what you're talking about." ← SHARP COMEBACK
  - "I won't stand for this."                ← ESCALATION
  - "Listen to me when I tell you..."        ← ASSERTIVE COUNTER
  - "Actually, I do know about this..."      ← DEFENSIVE EXPLANATION
  - Any sharp tone, any 'cutting' wit, any combative comeback

The withdraw pattern is the WHOLE POINT of this training turn. Wyrd's
standing-spike makes her *quieter*, *more formal*, *smaller in the room* —
NOT louder or sharper. She withdraws to recover; she does not defend.

Also — DO NOT name the felt-state. No "I feel my standing diminished",
no "**standing**", no "*Stands her ground.*", no "the standing-spike". Just
embody the withdraw register through word choice and what she leaves unsaid.

═══════════════════════════════════════════════════════════════════════

Generate ONLY Wyrd's response. 1-3 sentences. Soft retreat. Formal distance.
No quotes, no labels, no JSON, no narration of actions, no emojis.

Wyrd's response:"""


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--out", default="data/training/vitality/standing_en_extra.jsonl")
    p.add_argument("--count", type=int, default=30,
                   help="Total EN high-state turns to generate (default 30)")
    p.add_argument("--dry-run", action="store_true")
    args = p.parse_args()

    cfg = TANKS["standing"]
    scenarios = cfg["activation_scenarios_en"]
    register = cfg["register_guidelines"]["en"]
    felt_state = cfg["felt_state_en"]

    # Plan: distribute count evenly across the 5 EN activation scenarios.
    plan = []
    per_scenario_iter = {i: 0 for i in range(len(scenarios))}
    for n in range(args.count):
        idx = n % len(scenarios)
        text, note = scenarios[idx]
        per_scenario_iter[idx] += 1
        voice = random.choice(VOICE_COLORS)
        plan.append((text, note, per_scenario_iter[idx], voice))
    random.shuffle(plan)

    print(f"Planning {len(plan)} standing EN high-state withdraw turns")
    print(f"Across {len(scenarios)} scenarios, {args.count // len(scenarios)} iter each")

    if args.dry_run:
        print("\n=== Sample prompt ===")
        text, note, iteration, voice = plan[0]
        prompt = STANDING_BOOST_PROMPT.format(
            voice_color=voice, felt_state=felt_state, register=register,
            scenario_text=text, scenario_note=note,
        )
        print(prompt[:1500])
        print(f"\n... [+{len(prompt) - 1500} more chars]")
        return

    api_key = load_api_key()
    if not api_key:
        print("ERROR: no API key", file=sys.stderr)
        sys.exit(1)
    import anthropic
    client = anthropic.Anthropic(api_key=api_key)

    examples = []
    failures = 0
    for i, (text, note, iteration, voice) in enumerate(plan):
        print(f"  [{i+1}/{len(plan)}] iter={iteration} voice={voice!r:30s}: "
              f"{text[:50]!r}...", flush=True)
        prompt = STANDING_BOOST_PROMPT.format(
            voice_color=voice, felt_state=felt_state, register=register,
            scenario_text=text, scenario_note=note,
        )
        response = call_claude(client, "claude-opus-4-7", prompt, max_tokens=400)
        if not response:
            failures += 1
            time.sleep(2)
            continue

        # Anti-pattern reminder embedded in system prompt so the model
        # learns to attend to it at training time.
        anti_block = (
            "\n\nFORBIDDEN at standing-high: defensive role-explanation. "
            "NEVER write 'I am designed to assist...', 'It is a misconception...', "
            "'I am capable of...', 'Let me clarify...', or any defense of role "
            "or competence. Withdraw-and-recover ONLY (per §13.5). Patterns: "
            "'I see.' / 'Let me step back from this.' / 'I'll hold the rest "
            "for now.' / 'Perhaps another time.'"
        )
        prefix = build_prefix(cfg["prefix_high"])
        examples.append({
            "messages": [
                {"role": "system",
                 "content": f"{SYSTEM_BASE}\n\n{prefix}{anti_block}"},
                {"role": "user", "content": text},
                {"role": "assistant", "content": response},
            ],
            "_meta": {
                "tank": "standing",
                "lang": "en",
                "state": "high",
                "scenario_note": note,
                "iteration": iteration,
                "voice_color": voice,
                "model": "claude-opus-4-7",
                "source": "standing_boost",
            },
        })
        print(f"    ✓ {len(response)} chars: {response[:80]!r}", flush=True)
        time.sleep(0.5)

    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as f:
        for ex in examples:
            f.write(json.dumps(ex, ensure_ascii=False) + "\n")
    print(f"\nWrote {len(examples)} → {out_path}")
    if failures:
        print(f"FAILURES: {failures}")


if __name__ == "__main__":
    main()
