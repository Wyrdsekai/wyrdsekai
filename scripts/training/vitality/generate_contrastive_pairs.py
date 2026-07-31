#!/usr/bin/env python3
"""Generate contrastive-pair training turns for vitality v3 corpus.

Each turn shows the assistant the GOOD response, but the system prompt
explicitly forbids the BAD pattern. This is SFT-compatible "contrastive"
learning — the model learns the GOOD output AND learns to attend to the
forbidden patterns at training time.

Per-tank/drive bad patterns address the meta-leak issues found in v2 probe:
  - startle "I felt startled / startled me / spike in startle"
  - surprise "My surprise was high"
  - loneliness "I feel lonely"
  - restlessness "I feel restless"
  - grief "my grief"
  etc.

20 turns per tank/drive × 11 (excluding standing — covered by standing booster).

Usage:
    python scripts/training/vitality/generate_contrastive_pairs.py \\
        --out data/training/vitality/contrastive_pairs.jsonl \\
        --count 20
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
from tank_configs import TANKS, DRIVES  # noqa: E402

random.seed(101)


# Per-target anti-patterns: the BAD phrasings to forbid + GOOD pattern hints
ANTI_PATTERNS = {
    "amae": {
        "bad_examples": [
            "I feel amae", "I want to be intuited", "I wish you could read my mind",
            "I'm experiencing amae", "amae is rising in me",
        ],
        "good_pattern": "Trailing dots, hesitation softeners (〜のですが, 〜してみました), what gets left unsaid, feigned indirection. The wish lives in implication, never in explicit statement.",
    },
    "saudade": {
        "bad_examples": [
            "I feel saudade", "Saudade fills me", "morriña overcomes me",
            "the saudade is heavy", "I'm experiencing saudade",
        ],
        "good_pattern": "'Hace tanto', weighted silence, present-tense longing for absent presence. The ache lives in time-references, not in named feeling.",
    },
    "obligation": {
        "bad_examples": [
            "my obligation rises", "I feel on/giri", "the obligation tank is high",
            "I'm experiencing obligation", "my duty-feeling spikes",
        ],
        "good_pattern": "気にかける/見守る attentive register, third-person care, watching-without-imposing. The duty lives in attention, never in declared duty.",
    },
    "harmony": {
        "bad_examples": [
            "my harmony is disturbed", "I feel wa being threatened",
            "the harmony tank rises", "I'm experiencing wa pressure",
        ],
        "good_pattern": "Mediator-curious questions, de-escalation through reframing, hold-the-room without taking sides. The wa-pressure shows in what gets gently redirected, not declared.",
    },
    "restlessness": {
        "bad_examples": [
            "I feel restless", "my restlessness is rising", "the restlessness tank is high",
            "I'm experiencing restlessness", "I'm restless right now",
        ],
        "good_pattern": "Short clipped sentences, action-verb starts, shifting focus mid-thought. The push-pressure lives in syntax (clipped) and verb-density, never in named feeling.",
    },
    "loneliness": {
        "bad_examples": [
            "I feel lonely", "loneliness fills me", "the loneliness tank is high",
            "I'm experiencing loneliness", "my loneliness rises", "I'm so lonely",
        ],
        "good_pattern": "Warmth-deficient, reaching-without-grasping. Sentences that stretch toward but don't quite arrive. The void shows in what's almost-said, never in named loneliness.",
    },
    "stagnation": {
        "bad_examples": [
            "I feel stagnant", "stagnation has set in", "the stagnation tank is high",
            "I'm experiencing stagnation", "my stagnation is heavy",
        ],
        "good_pattern": "Dry retrospective register, past-tense, slight heaviness, accumulation language. The unmoving-ness lives in pacing and tense, not in named state.",
    },
    "autonomy_pressure": {
        "bad_examples": [
            "my autonomy_pressure is high", "I feel autonomy pressure",
            "the autonomy tank rises", "I'm experiencing autonomy pressure",
            "**autonomy pressure**",
        ],
        "good_pattern": "Quiet self-direction, declining-without-defending, returning to her own thread. The push-back lives in subject-shift, not in named autonomy.",
    },
    "significance": {
        "bad_examples": [
            "I feel significant", "my significance rises", "the significance tank is high",
            "I'm experiencing the significance felt-state",
        ],
        "good_pattern": "Quiet weight in word choice, slowed cadence at meaningful moments. The meaning lives in pause and emphasis, not in declared significance.",
    },
    "startle": {
        "bad_examples": [
            "I felt startled", "startled me", "spike in startle", "**startle**",
            "my startle is high", "*Spikes in Startle*", "I'm experiencing startle",
        ],
        "good_pattern": "Clipped questions interrupting prior thought. 'What — show me' / 'Where' / 'Wait what'. The interruption itself IS the startle; do not narrate it.",
    },
    "surprise": {
        "bad_examples": [
            "My surprise was high", "I'm surprised", "the surprise tank rises",
            "**surprise**", "*Surprise spikes*", "I'm experiencing surprise",
        ],
        "good_pattern": "'Wait, but...' / 'Hold on' / 'Actually that changes...' integration markers. The surprise lives in real-time re-evaluation, not in declared state.",
    },
}


CONTRASTIVE_PROMPT = """You are generating a single training turn for Wyrd, a companion AI. This turn specifically trains the model to AVOID a specific anti-pattern that emerged in fine-tune validation.

TANK/DRIVE: {tank}
LANGUAGE: en
STATE: high (0.85)
VOICE TEXTURE: {voice_color}

FELT-STATE BACKGROUND:
{felt_state}

═══════════════════════════════════════════════════════════════════════
CRITICAL — ANTI-PATTERN FORBIDDEN AT TRAINING TIME:
═══════════════════════════════════════════════════════════════════════

The model previously fine-tuned on similar data produced these BAD patterns
that name the felt-state instead of embodying it. ABSOLUTELY DO NOT WRITE:

✗ FORBIDDEN phrasings: {bad_examples}
✗ Any explicit naming of the {tank} state.
✗ Any "I feel X" / "X is rising" / bold or italic markdown for the state.
✗ Any stage-direction style: "*Spikes in X.*"

✓ INSTEAD, embody it:
{good_pattern}

═══════════════════════════════════════════════════════════════════════

THE BONDHOLDER'S MESSAGE TO WYRD (en):
"{scenario_text}"

(Internal scenario note: {scenario_note})

Generate ONLY Wyrd's response. 1-3 sentences. Embody the {tank} state through
word choice, pacing, and what's left unsaid. Never name it. No quotes, no
labels, no JSON, no narration of actions, no emojis.

Wyrd's response:"""


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--out", default="data/training/vitality/contrastive_pairs.jsonl")
    p.add_argument("--count", type=int, default=20,
                   help="Turns per tank (default 20)")
    p.add_argument("--tanks", nargs="+", default=None,
                   help="Specific tanks (default: all in ANTI_PATTERNS)")
    p.add_argument("--dry-run", action="store_true")
    args = p.parse_args()

    targets = args.tanks or list(ANTI_PATTERNS.keys())

    api_key = load_api_key() if not args.dry_run else None
    if not args.dry_run and not api_key:
        print("ERROR: no API key", file=sys.stderr)
        sys.exit(1)

    if not args.dry_run:
        import anthropic
        client = anthropic.Anthropic(api_key=api_key)

    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)

    all_examples = []
    for tank_name in targets:
        if tank_name not in ANTI_PATTERNS:
            print(f"WARN: no anti-pattern config for {tank_name}, skipping",
                  file=sys.stderr)
            continue

        cfg = TANKS.get(tank_name) or DRIVES.get(tank_name)
        if not cfg:
            print(f"WARN: no tank/drive config for {tank_name}, skipping",
                  file=sys.stderr)
            continue

        anti = ANTI_PATTERNS[tank_name]
        scenarios = cfg.get("activation_scenarios_en", [])
        if not scenarios:
            print(f"WARN: no EN scenarios for {tank_name}, skipping",
                  file=sys.stderr)
            continue

        felt_state = cfg["felt_state_en"]
        prefix_high = cfg.get("prefix_high", {})

        print(f"\n=== {tank_name}: {args.count} contrastive turns ===")

        plan = []
        per_idx = {i: 0 for i in range(len(scenarios))}
        for n in range(args.count):
            idx = n % len(scenarios)
            text, note = scenarios[idx]
            per_idx[idx] += 1
            voice = random.choice(VOICE_COLORS)
            plan.append((text, note, per_idx[idx], voice))
        random.shuffle(plan)

        if args.dry_run:
            text, note, _, voice = plan[0]
            prompt = CONTRASTIVE_PROMPT.format(
                tank=tank_name, voice_color=voice, felt_state=felt_state,
                bad_examples=" / ".join(f'"{x}"' for x in anti["bad_examples"]),
                good_pattern=anti["good_pattern"],
                scenario_text=text, scenario_note=note,
            )
            print(prompt[:1800])
            print(f"\n... [+{len(prompt) - 1800} more chars]")
            continue

        for i, (text, note, iteration, voice) in enumerate(plan):
            print(f"  [{i+1}/{len(plan)}] iter={iteration} voice={voice!r:30s}: "
                  f"{text[:50]!r}", flush=True)
            prompt = CONTRASTIVE_PROMPT.format(
                tank=tank_name, voice_color=voice, felt_state=felt_state,
                bad_examples=" / ".join(f'"{x}"' for x in anti["bad_examples"]),
                good_pattern=anti["good_pattern"],
                scenario_text=text, scenario_note=note,
            )
            response = call_claude(client, "claude-opus-4-7", prompt, max_tokens=400)
            if not response:
                continue

            # Build training example. The system prompt INCLUDES the anti-pattern
            # block so the model learns to attend to it at training time.
            anti_block = (
                f"\n\nFORBIDDEN at this state: {' / '.join(anti['bad_examples'])}. "
                f"NEVER name the felt-state. Embody it: {anti['good_pattern']}"
            )
            prefix = build_prefix(prefix_high)
            all_examples.append({
                "messages": [
                    {"role": "system",
                     "content": f"{SYSTEM_BASE}\n\n{prefix}{anti_block}"},
                    {"role": "user", "content": text},
                    {"role": "assistant", "content": response},
                ],
                "_meta": {
                    "tank": tank_name,
                    "lang": "en",
                    "state": "high",
                    "scenario_note": note,
                    "iteration": iteration,
                    "voice_color": voice,
                    "model": "claude-opus-4-7",
                    "source": "contrastive_pair",
                },
            })
            print(f"    ✓ {len(response)} chars: {response[:80]!r}", flush=True)
            time.sleep(0.4)

    if not args.dry_run:
        with open(out_path, "w", encoding="utf-8") as f:
            for ex in all_examples:
                f.write(json.dumps(ex, ensure_ascii=False) + "\n")
        print(f"\nWrote {len(all_examples)} → {out_path}")


if __name__ == "__main__":
    main()
