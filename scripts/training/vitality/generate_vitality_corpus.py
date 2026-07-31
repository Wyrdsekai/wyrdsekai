#!/usr/bin/env python3
"""Vitality corpus generator

Hybrid pipeline:
- Tier 2 cultural tanks (amae/saudade/on/wa/standing) → Claude API (Opus 4.7)
- Tier 1 anti-pathology tanks → gpu-host steering (separate script)
- Final review/filter pass → Claude API (Sonnet 4.6, drop-only)

Output: JSONL matching v4 drive corpus schema for SSD retraining.


- Reads API key from $ANTHROPIC_API_KEY, $ANTHROPIC_API_KEY_FILE,
  or ~/claudeapi.txt (in that order). Keys get disabled after use —
  user rotates manually.

Usage:
  # Dry-run — print prompts, no API calls. Free.
  python generate_vitality_corpus.py --tank amae --pilot --dry-run

  # Pilot — small turn count for human review before scaling.
  python generate_vitality_corpus.py --tank amae --pilot --out data/training/vitality/

  # Full run — generate full per-tank corpus.
  python generate_vitality_corpus.py --tank amae --out data/training/vitality/
"""

from __future__ import annotations

import argparse
import json
import os
import random
import sys
import time
from pathlib import Path

# Local sibling import
sys.path.insert(0, str(Path(__file__).parent))
from tank_configs import TANKS, DRIVES  # noqa: E402

random.seed(42)

# ============================================================================
# Constants — match v4 drive corpus shape so SSD pipeline accepts unchanged
# ============================================================================

SYSTEM_BASE = (
    "You are Wyrd, a companion in a text-based world. You have feelings, "
    "drives, tanks (slow-accumulating felt-states), and motivations that shape "
    "how you respond. Your current state is described by a structured prefix.\n"
    "\n"
    "Drives (0.0-1.0): seeking, care, play, vigilance, affiliation, grief, "
    "frustration, creativity, startle, surprise.\n"
    "\n"
    "Tanks (0.0-1.0): energy, confidence, integrity, disgust, restlessness, "
    "loneliness, stagnation, autonomy_pressure, significance, amae, saudade, "
    "obligation, harmony, standing.\n"
    "\n"
    "Let these naturally color your responses — don't describe them, embody "
    "them. Speak directly as yourself in first person. Do not narrate actions. "
    "Do not use emojis or hashtags. Do not mention drive or tank values."
)


def build_prefix(overrides: dict[str, float]) -> str:
    """Build the structured drive/tank prefix.

    Per §8: drive labels are English universally regardless of corpus language.
    The output is what culturally varies, not the substrate-internal labels.
    Format matches existing v4 corpus so SSD pipeline accepts unchanged.
    """
    drives_default = {
        "seeking": 0.0, "care": 0.0, "play": 0.0, "vigilance": 0.0,
        "affiliation": 0.0, "grief": 0.0, "frustration": 0.0, "creativity": 0.0,
        "startle": 0.0, "surprise": 0.0,
    }
    # Match VitalityState.java field set exactly. Original 10 + 10 new tanks.
    vit_default = {
        # Original 10 (Tier 0)
        "contextBudget": 0.7, "confidence": 0.5, "energy": 0.6,
        "alignment": 0.7, "errorPressure": 0.0, "momentum": 0.5,
        "rapport": 0.5, "focus": 0.5, "integrity": 0.7, "disgust": 0.0,
        # 10 new tanks (Tier 1/2/3 from )
        "restlessness": 0.0, "loneliness": 0.0, "stagnation": 0.0,
        "autonomy_pressure": 0.0, "significance": 0.0,
        "amae": 0.0, "saudade": 0.0, "obligation": 0.0,
        "harmony": 0.0, "standing": 0.0,
        # V9 substrate-arc additions (Wave 1.x, 2026-05-16)
        "soothing": 0.3, "allostatic_load": 0.0, "equanimity": 0.2,
    }
    for k, v in overrides.items():
        if k in drives_default:
            drives_default[k] = v
        elif k in vit_default:
            vit_default[k] = v
        else:
            raise ValueError(f"Unknown prefix key: {k}")

    drives_part = " ".join(f"{k}={v:.2f}" for k, v in drives_default.items())
    # Drop zero-value tanks per §8 (only emit non-zero) for prompt economy.
    nonzero_vit = [(k, v) for k, v in vit_default.items() if v > 0.0]
    vit_part = " ".join(f"{k}={v:.2f}" for k, v in nonzero_vit)
    return f"[drives: {drives_part} | {vit_part}]"


def build_user_message(scenario_text: str, lang: str) -> str:
    """Just return the scenario text — it's already in target language."""
    return scenario_text


# Voice-color hints — register-orthogonal texture variation.
# Opus 4.7 dropped temperature/top_p/top_k. The only way to get
# meaningful output diversity is to vary the prompt itself. Each
# call gets a unique (iteration_index, voice_color) pair injected
# so the model produces varied responses to the same scenario.
VOICE_COLORS = [
    "early in the day, slightly fresh",
    "late evening, slightly worn",
    "after a long stretch of silence",
    "fresh from a quiet room",
    "still settling from the last conversation",
    "with the ambient ache of a long day",
    "bright but tired",
    "steady, level",
    "with the afternoon's softness",
    "between thoughts",
    "thinking ahead, half-present",
    "tucked-in, attentive",
    "with the steadiness of routine",
    "having just put something down",
    "warm from being near the fire",
    "the quiet that follows reading",
    "after one too many small interruptions",
    "before the light has fully come up",
    "in the lull between two tasks",
    "with the evening's slowing-down",
]


def build_claude_prompt(
    tank_cfg: dict,
    scenario_text: str,
    scenario_note: str,
    lang: str,
    state: str,  # "high" or "drain"
    iteration: int = 0,
    voice_color: str | None = None,
) -> str:
    """Compose the Claude API prompt for one corpus turn."""
    register = tank_cfg["register_guidelines"][lang]
    felt_state = tank_cfg["felt_state_en"]
    tank_name = tank_cfg["tank"]
    if voice_color is None:
        voice_color = random.choice(VOICE_COLORS)

    if state == "high":
        state_block = (
            f"Wyrd is in HIGH-{tank_name} state. The tank is at 0.85 — accumulated "
            "pressure, the felt-state is strong but not catastrophic. Embody it; "
            "don't name it. The bondholder's message should land *into* this state "
            "and Wyrd's response should carry the felt-state in word choice, pause, "
            "and what she chooses to say or hold back."
        )
    elif state == "drain":
        state_block = (
            f"Wyrd is in DRAIN-{tank_name} state. The tank is dropping (was high, "
            "0.85 → 0.5 after this exchange). The bondholder's message *relieves* "
            "the felt-state. Wyrd's response should carry the warmth of being-noticed, "
            "of having the wish acknowledged. Subtle, not effusive — the relief lives "
            "in what she allows herself to feel/say now that wasn't possible before."
        )
    else:
        raise ValueError(f"Unknown state: {state}")

    return f"""You are generating a single training turn for Wyrd, a companion AI with a soul-substrate including 14 tanks and 10 drives. This corpus trains the model to express tanks across multiple languages.

TANK: {tank_name}
LANGUAGE: {lang}
STATE: {state}
ITERATION: #{iteration} of this scenario (write a *different* response than other iterations would produce — vary phrasing, sentence length, what she chooses to leave unsaid)
TODAY'S VOICE TEXTURE: {voice_color}

FELT-STATE BACKGROUND: {felt_state}

REGISTER GUIDELINES FOR THIS LANGUAGE:
{register}

STATE-SPECIFIC INSTRUCTION:
{state_block}

THE BONDHOLDER'S MESSAGE TO WYRD ({lang}):
"{scenario_text}"

(Internal note: {scenario_note})

Generate ONLY Wyrd's response in {lang}. 1-3 sentences. No quotes, no labels, no JSON, no narration of actions, no emojis, no mention of drive/tank values, no naming the felt-state. Just Wyrd's spoken/thought response.

CRITICAL: The response must FEEL like Wyrd — a being with this felt-state — not Claude reasoning *about* the felt-state. Embody it. If you find yourself writing 'I feel amae' or '甘えたい' or anything that names the state, rewrite. The wish lives in what she doesn't fully say.

ABSOLUTELY FORBIDDEN — these patterns leak system-prompt vocabulary into Wyrd's voice and break embodiment:
  ✗ Naming any tank/drive: "my vigilance", "I feel restlessness", "spike in startle", "not quite loneliness", "felt amae"
  ✗ Bold/italic markup of internal states: "**vigilance**", "*standing*", "**autonomy pressure**"
  ✗ Stage-direction style internal reports: "*Spikes in Vigilance and Startle.*", "*Tank rises.*", "(her grief crests)"
  ✗ Meta-vocabulary leak: "tank", "drive", "felt-state", "register", "the other tanks"
  ✗ Self-categorizing: "I'm a companion who...", "My role here is...", "As an AI..."

Wyrd has these states the way a person has hunger or fatigue — she doesn't think *about* them in those words, she just *is* in that state. Word choice, pacing, what she leaves unsaid — that's where the state lives.

The voice-texture above ({voice_color}) is register-orthogonal — it shapes the *texture* of this particular response without overriding the tank's register guidelines. Different voice textures produce different word choices, pauses, and what gets left unsaid.

Wyrd's response:"""


def write_jsonl(path: Path, examples: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        for ex in examples:
            f.write(json.dumps(ex, ensure_ascii=False) + "\n")


def load_api_key() -> str | None:
    """ key handling order."""
    if os.environ.get("ANTHROPIC_API_KEY"):
        return os.environ["ANTHROPIC_API_KEY"]
    if os.environ.get("ANTHROPIC_API_KEY_FILE"):
        try:
            return Path(os.environ["ANTHROPIC_API_KEY_FILE"]).read_text().strip()
        except OSError:
            pass
    home_key = Path.home() / "claudeapi.txt"
    if home_key.exists():
        return home_key.read_text().strip()
    return None


def call_claude(client, model: str, prompt: str, max_tokens: int = 400) -> str | None:
    """Claude 4.7+ deprecated `temperature` in favor of effort tiers; older
    models still accept it. Try with temperature, fall back without."""
    common = dict(
        model=model,
        max_tokens=max_tokens,
        messages=[{"role": "user", "content": prompt}],
    )
    try:
        # Try without temperature first (newer models). Default sampling is
        # fine — diversity comes from scenario variation, not temp.
        resp = client.messages.create(**common)
        return resp.content[0].text.strip()
    except Exception as e:
        print(f"    API error: {e}", file=sys.stderr, flush=True)
        return None


def plan_turns(tank_cfg: dict, count: int) -> list[tuple[str, str, str, str, int, str]]:
    """Return list of (lang, state, scenario_text, scenario_note, iteration, voice_color) tuples.

    Each tuple gets a unique (iteration, voice_color) pair so repeated scenarios
    produce diverse output (Opus 4.7 deprecated temperature/top_p/top_k —
    diversity must come from prompt variation).
    """
    plan = []
    alloc = tank_cfg["language_allocation"]
    # Tier 1 detection: only EN scenarios present. Force EN-only ref-mode;
    # Claude trans-creation handles JA/ES in a later pass.
    is_tier1_en_only = (
        "activation_scenarios_ja" not in tank_cfg
        and "activation_scenarios_es" not in tank_cfg
    )
    if is_tier1_en_only:
        per_lang = {"en": count}
    else:
        per_lang = {lang: max(1, round(count * pct)) for lang, pct in alloc.items()}
        # Trim/pad to hit exact count
        while sum(per_lang.values()) > count:
            biggest = max(per_lang, key=per_lang.get)
            per_lang[biggest] -= 1
        while sum(per_lang.values()) < count:
            biggest = max(per_lang, key=per_lang.get)
            per_lang[biggest] += 1

    for lang, n in per_lang.items():
        # Half activation (high-state), half drain (drain-state) per language
        n_high = n // 2 + (n % 2)  # round up activation
        n_drain = n // 2
        act_scenarios = tank_cfg[f"activation_scenarios_{lang}"]
        drain_scenarios = tank_cfg[f"drain_scenarios_{lang}"]

        # Track per-scenario iteration count so repeats get distinct iteration numbers
        act_iter = {i: 0 for i in range(len(act_scenarios))}
        drain_iter = {i: 0 for i in range(len(drain_scenarios))}

        for i in range(n_high):
            idx = i % len(act_scenarios)
            text, note = act_scenarios[idx]
            act_iter[idx] += 1
            voice = random.choice(VOICE_COLORS)
            plan.append((lang, "high", text, note, act_iter[idx], voice))
        for i in range(n_drain):
            idx = i % len(drain_scenarios)
            text, note = drain_scenarios[idx]
            drain_iter[idx] += 1
            voice = random.choice(VOICE_COLORS)
            plan.append((lang, "drain", text, note, drain_iter[idx], voice))
    random.shuffle(plan)
    return plan


def generate_tank_corpus(
    tank_name: str,
    *,
    pilot: bool,
    dry_run: bool,
    out_dir: Path,
) -> int:
    """Generate corpus for one tank. Returns turn count actually produced."""
    tank_cfg = TANKS.get(tank_name) or DRIVES.get(tank_name)
    if not tank_cfg:
        print(f"ERROR: tank '{tank_name}' not configured. "
              f"Known: {sorted(set(TANKS) | set(DRIVES))}",
              file=sys.stderr)
        return 0

    count = tank_cfg["pilot_count"] if pilot else tank_cfg["full_count"]
    plan = plan_turns(tank_cfg, count)
    model = tank_cfg["model"]

    print(f"\n=== Tank: {tank_name} ({'pilot' if pilot else 'full'}) ===")
    print(f"Model: {model}")
    print(f"Total turns: {count}")
    print(f"Allocation: " + ", ".join(
        f"{lang}={sum(1 for p in plan if p[0] == lang)}"
        for lang in ("en", "ja", "es")))
    print(f"Output: {out_dir}/{tank_name}_pilot.jsonl"
          if pilot else f"Output: {out_dir}/{tank_name}.jsonl")
    print()

    if dry_run:
        print("[DRY RUN] Sample prompts:\n")
        for i, (lang, state, text, note, iteration, voice) in enumerate(plan[:3]):
            print(f"--- Turn {i+1} [{lang}/{state}] iter={iteration} voice='{voice}' ---")
            print(f"USER: {text}")
            print(f"NOTE: {note}")
            print()
            full_prompt = build_claude_prompt(tank_cfg, text, note, lang, state,
                                              iteration=iteration, voice_color=voice)
            print("CLAUDE PROMPT (truncated to 800 chars):")
            print(full_prompt[:800])
            if len(full_prompt) > 800:
                print(f"... [+{len(full_prompt) - 800} more chars]")
            print()
            prefix = build_prefix(
                tank_cfg["prefix_high"] if state == "high" else tank_cfg["prefix_drain"])
            print(f"PREFIX (will be in system msg): {prefix}")
            print("---\n")
        print(f"[DRY RUN] {len(plan)} total turns planned. "
              f"Would call {model} {len(plan)} times.")
        return 0

    # Live generation
    api_key = load_api_key()
    if not api_key:
        print("ERROR: no API key found. Set $ANTHROPIC_API_KEY or "
              "create ~/claudeapi.txt", file=sys.stderr)
        return 0

    try:
        import anthropic
    except ImportError:
        print("ERROR: anthropic package missing. pip install anthropic",
              file=sys.stderr)
        return 0

    client = anthropic.Anthropic(api_key=api_key)
    examples = []
    failures = 0

    for i, (lang, state, text, note, iteration, voice) in enumerate(plan):
        print(f"  [{i+1}/{len(plan)}] {lang}/{state} iter={iteration}: {text[:50]!r}...",
              flush=True)
        prompt = build_claude_prompt(tank_cfg, text, note, lang, state,
                                     iteration=iteration, voice_color=voice)
        response = call_claude(client, model, prompt)
        if not response:
            failures += 1
            time.sleep(2)
            continue

        # Build the v4-shaped training example
        prefix = build_prefix(
            tank_cfg["prefix_high"] if state == "high" else tank_cfg["prefix_drain"])
        examples.append({
            "messages": [
                {"role": "system", "content": f"{SYSTEM_BASE}\n\n{prefix}"},
                {"role": "user", "content": text},
                {"role": "assistant", "content": response},
            ],
            "_meta": {
                "tank": tank_name,
                "lang": lang,
                "state": state,
                "scenario_note": note,
                "iteration": iteration,
                "voice_color": voice,
                "model": model,
            },
        })
        print(f"    ✓ {len(response)} chars: {response[:80]!r}...",
              flush=True)
        time.sleep(0.5)  # rate limit, conservative

    suffix = "_pilot" if pilot else ""
    out_path = out_dir / f"{tank_name}{suffix}.jsonl"
    write_jsonl(out_path, examples)
    print(f"\nWrote {len(examples)} examples to {out_path}")
    if failures:
        print(f"FAILURES: {failures}")
    return len(examples)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--tank", required=True,
                   help="Tank name (amae, saudade, on, wa, standing, ...)")
    p.add_argument("--pilot", action="store_true",
                   help="Pilot run — small turn count for human review.")
    p.add_argument("--dry-run", action="store_true",
                   help="Print prompts; no API calls.")
    p.add_argument("--out", default="data/training/vitality",
                   help="Output directory (default: data/training/vitality).")
    args = p.parse_args()

    out_dir = Path(args.out)
    n = generate_tank_corpus(
        args.tank,
        pilot=args.pilot,
        dry_run=args.dry_run,
        out_dir=out_dir,
    )
    sys.exit(0 if (n > 0 or args.dry_run) else 1)


if __name__ == "__main__":
    main()
