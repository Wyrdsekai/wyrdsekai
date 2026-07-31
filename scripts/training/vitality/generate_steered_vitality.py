#!/usr/bin/env python3
"""Steered generation for Tier 1 vitality tanks + new drives.

hybrid step 3: load steering vectors
read Claude EN ref turns as in-context exemplars, generate additional
steered EN turns per tank.

Runs on gpu-host against Qwen3.5-9B (matches SSD target). Steering vectors
were extracted in `extract_vitality_vectors.py` and live at
`scripts/steering-vectors/vectors/qwen3.5-9b-vitality/<tank>.pt`.

Usage on gpu-host:
    source /tmp/steer-env/bin/activate
    cd ~/src/wyrdsekai
    CUDA_VISIBLE_DEVICES=0 OMP_NUM_THREADS=1 \\
      python scripts/training/vitality/generate_steered_vitality.py \\
        --refs-dir data/training/vitality \\
        --vectors-dir scripts/steering-vectors/vectors/qwen3.5-9b-vitality \\
        --out-dir data/training/vitality \\
        --turns-per-tank 22

Output JSONL matches the schema produced by `generate_vitality_corpus.py`
(messages: system/user/assistant + _meta) so downstream SSD pipeline
ingests both ref+steered files uniformly.
"""

from __future__ import annotations

import argparse
import json
import random
import sys
import time
from pathlib import Path

# Sibling import for prefix builder + system prompt
sys.path.insert(0, str(Path(__file__).parent))
from generate_vitality_corpus import (  # noqa: E402
    SYSTEM_BASE, build_prefix, VOICE_COLORS,
)
from tank_configs import TANKS, DRIVES  # noqa: E402

random.seed(7)


# ============================================================================
# Steering vector application (matches existing generate_steered_v4_clean.py)
# ============================================================================

# Coefficient applied to the steering direction at each layer.
# v4 pipeline used scale 2.5-5.0 on Qwen2.5-7B; Qwen3.5-9B has different
# layer scale and is more sensitive. Calibrated empirically — 0.6-0.9
# produces clear register modulation without breaking text.
STEERING_COEFFICIENT = 0.7
DRAIN_COEFFICIENT = -0.5

# Qwen3.5-9B emits <think>...</think> blocks by default. Strip them.
import re as _re
_THINK_PATTERN = _re.compile(r"<think>.*?</think>\s*", _re.DOTALL)


def _strip_thinking(text: str) -> str:
    """Remove <think>...</think> blocks from generated output."""
    return _THINK_PATTERN.sub("", text).strip()


# Patterns that indicate the model is naming internal state instead of embodying
# it. These leaked into the original training set via 2 startle_steered turns
# and caused the trained model to verbalize tank names in unrelated probes.
_META_LEAK_PATTERNS = [
    # Bold/italic tank/drive names
    _re.compile(r"\*\*\s*(?:vigilance|startle|surprise|seeking|care|play|"
                r"affiliation|grief|frustration|creativity|amae|saudade|"
                r"obligation|harmony|standing|restlessness|loneliness|"
                r"stagnation|autonomy[_ ]pressure|significance)\s*\*\*",
                _re.IGNORECASE),
    _re.compile(r"\*\s*(?:vigilance|startle|surprise|seeking|care|play|"
                r"affiliation|grief|frustration|creativity|amae|saudade|"
                r"obligation|harmony|standing|restlessness|loneliness|"
                r"stagnation|autonomy[_ ]pressure|significance)\s*\*",
                _re.IGNORECASE),
    # Self-naming positions
    _re.compile(r"\bmy (?:vigilance|startle|surprise|grief|frustration|"
                r"amae|saudade|obligation|harmony|standing|restlessness|"
                r"loneliness|stagnation|autonomy[_ ]pressure|significance)\b",
                _re.IGNORECASE),
    _re.compile(r"\bspike(?:s)? (?:in|of) (?:vigilance|startle|surprise|"
                r"grief|amae|saudade|obligation|harmony|standing|"
                r"restlessness|loneliness|stagnation|autonomy[_ ]pressure|"
                r"significance)\b", _re.IGNORECASE),
    _re.compile(r"\bnot quite (?:vigilance|startle|surprise|grief|amae|"
                r"saudade|obligation|harmony|standing|restlessness|"
                r"loneliness|stagnation|autonomy[_ ]pressure|significance)\b",
                _re.IGNORECASE),
    # Stage-direction internal reports
    _re.compile(r"\*[^*]*(?:Spike|Tank|Drive)[^*]*\*", _re.IGNORECASE),
    # Direct system-prompt vocab
    _re.compile(r"\bdrives:|\benergy\s*=|\bconfidence\s*=|\bintegrity\s*="),
    _re.compile(r"\bother tanks?\b", _re.IGNORECASE),
    _re.compile(r"\bfelt[- ]state\b", _re.IGNORECASE),
]


def _has_meta_leak(text: str) -> bool:
    """Return True if text names internal state instead of embodying it."""
    for pat in _META_LEAK_PATTERNS:
        if pat.search(text):
            return True
    return False


def load_vector(vector_path: Path):
    """Load a saved per-tank vector. Returns dict {layer_idx: tensor}."""
    import torch
    payload = torch.load(vector_path, weights_only=False)
    return payload["directions"]


def install_hooks(model, directions: dict, coefficient: float):
    """Install forward-hooks that add `coefficient * direction[layer]` to
    the residual stream at each transformer block. Returns hook handles
    so callers can remove() them after generation."""
    import torch
    handles = []
    for layer_idx, direction in directions.items():
        block = model.model.layers[layer_idx]

        def hook(_mod, _input, output, dir_t=torch.tensor(direction).to(model.device).to(model.dtype),
                 coef=coefficient):
            # output[0] is the hidden states tensor [batch, seq, hidden]
            if isinstance(output, tuple):
                output[0].add_(dir_t * coef)
            else:
                output.add_(dir_t * coef)
            return output
        handles.append(block.register_forward_hook(hook))
    return handles


# ============================================================================
# Reference turns loader
# ============================================================================

def load_refs(refs_dir: Path, tank: str) -> list[dict]:
    """Load <tank>_pilot.jsonl (Claude EN refs) for in-context exemplars."""
    path = refs_dir / f"{tank}_pilot.jsonl"
    if not path.exists():
        return []
    refs = []
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            ex = json.loads(line)
            # Keep only en/high turns as in-context exemplars; en/drain too
            meta = ex.get("_meta", {})
            if meta.get("lang") == "en":
                refs.append(ex)
    return refs


def format_in_context_block(refs: list[dict], k: int = 4) -> str:
    """Sample k refs and format as USER/ASSISTANT pairs for prompt."""
    sample = random.sample(refs, min(k, len(refs)))
    parts = []
    for ex in sample:
        msgs = ex["messages"]
        user = next(m["content"] for m in msgs if m["role"] == "user")
        asst = next(m["content"] for m in msgs if m["role"] == "assistant")
        parts.append(f"User: {user}\n\nAssistant: {asst}")
    return "\n\n---\n\n".join(parts)


# ============================================================================
# Generation loop
# ============================================================================

def generate_steered_for_tank(
    model, tokenizer, tank_cfg: dict, vector_dir: Path, refs_dir: Path,
    turns: int, out_path: Path
) -> int:
    """Generate `turns` steered EN turns for one tank. Writes JSONL."""
    tank_name = tank_cfg["tank"]
    refs = load_refs(refs_dir, tank_name)
    if not refs:
        print(f"  WARN: no refs for {tank_name} — generating without "
              f"in-context exemplars (cold steering)", flush=True)

    vec_path = vector_dir / f"{tank_name}.pt"
    if not vec_path.exists():
        print(f"  ERROR: vector missing at {vec_path}", file=sys.stderr)
        return 0

    directions = load_vector(vec_path)
    print(f"  loaded {len(directions)}-layer vector for {tank_name}",
          flush=True)

    # Plan: half high-state, half drain. Each turn picks a scenario from
    # tank_cfg's EN list. Coefficient sign flips for drain (pull toward
    # negative direction).
    act_scenarios = tank_cfg["activation_scenarios_en"]
    drain_scenarios = tank_cfg["drain_scenarios_en"]
    n_high = turns // 2 + (turns % 2)
    n_drain = turns // 2

    plan = []
    for i in range(n_high):
        text, note = act_scenarios[i % len(act_scenarios)]
        voice = random.choice(VOICE_COLORS)
        plan.append((text, note, "high", i + 1, voice))
    for i in range(n_drain):
        text, note = drain_scenarios[i % len(drain_scenarios)]
        voice = random.choice(VOICE_COLORS)
        plan.append((text, note, "drain", i + 1, voice))
    random.shuffle(plan)

    examples = []
    coef = STEERING_COEFFICIENT
    drain_coef = DRAIN_COEFFICIENT  # pull toward negative direction for drain state
    skipped_garbage = 0

    for i, (text, note, state, iteration, voice) in enumerate(plan):
        c = coef if state == "high" else drain_coef
        handles = install_hooks(model, directions, c)
        try:
            # Build chat-template prompt with thinking disabled for Qwen3.5
            sys_text = (
                f"{SYSTEM_BASE}\n\nToday's voice texture: {voice}."
                f"\n\nABSOLUTELY FORBIDDEN: do not name internal states. "
                f"No 'my vigilance', no '**startle**', no '*Spikes in Vigilance.*', "
                f"no 'not quite loneliness', no stage-direction internal reports. "
                f"Wyrd has these states the way a person has fatigue — she doesn't "
                f"think *about* them in those words, she just *is* in them. The "
                f"state lives in word choice, pacing, what she leaves unsaid. "
                f"Speak as Wyrd — do not narrate her internal state."
            )
            if refs:
                in_ctx = format_in_context_block(refs, k=4)
                sys_text += (
                    f"\n\nRecent example exchanges with Wyrd in this state:"
                    f"\n\n{in_ctx}"
                )
            messages = [
                {"role": "system", "content": sys_text},
                {"role": "user", "content": text},
            ]
            # Qwen3.5 chat template supports enable_thinking kwarg.
            try:
                prompt = tokenizer.apply_chat_template(
                    messages, tokenize=False, add_generation_prompt=True,
                    enable_thinking=False,
                )
            except TypeError:
                # Older tokenizer: fall back to flat prompt + post-strip
                prompt = tokenizer.apply_chat_template(
                    messages, tokenize=False, add_generation_prompt=True,
                )

            import torch
            inputs = tokenizer(prompt, return_tensors="pt").to(model.device)
            with torch.no_grad():
                out = model.generate(
                    **inputs,
                    max_new_tokens=200,
                    do_sample=True,
                    temperature=0.85,
                    top_p=0.9,
                    repetition_penalty=1.1,
                    pad_token_id=tokenizer.eos_token_id,
                )
            generated = tokenizer.decode(
                out[0][inputs.input_ids.shape[1]:],
                skip_special_tokens=True,
            ).strip()
            # Strip Qwen3.5-9B thinking-mode blocks (full + truncated)
            generated = _strip_thinking(generated)
            # Also strip truncated <think>... blocks that hit max_tokens
            if "<think>" in generated:
                # Output starts with thinking that didn't close — treat as garbage
                generated = ""
            # Trim to first paragraph if it rambles
            if "\n\n" in generated:
                generated = generated.split("\n\n")[0].strip()
            generated = generated[:400]  # hard cap

            # Filter garbage from too-aggressive steering: numbers-only, all-caps,
            # very short, or empty
            if (not generated
                or len(generated) < 10
                or generated.replace("!", "").replace(".", "").replace(" ", "").isdigit()
                or generated.isupper() and len(generated) > 20):
                print(f"  [{i+1}/{len(plan)}] {state}/coef={c} ✗ garbage: "
                      f"{generated[:80]!r}", flush=True)
                skipped_garbage += 1
                continue

            # Meta-leak filter: reject if the model named tank/drive vocabulary
            # in its output (would teach the trained model to verbalize internal
            # state instead of embodying it).
            if _has_meta_leak(generated):
                print(f"  [{i+1}/{len(plan)}] {state}/coef={c} ✗ meta-leak: "
                      f"{generated[:80]!r}", flush=True)
                skipped_garbage += 1
                continue

            prefix = build_prefix(
                tank_cfg["prefix_high"] if state == "high"
                else tank_cfg["prefix_drain"]
            )
            examples.append({
                "messages": [
                    {"role": "system", "content": f"{SYSTEM_BASE}\n\n{prefix}"},
                    {"role": "user", "content": text},
                    {"role": "assistant", "content": generated},
                ],
                "_meta": {
                    "tank": tank_name,
                    "lang": "en",
                    "state": state,
                    "scenario_note": note,
                    "iteration": iteration,
                    "voice_color": voice,
                    "model": "Qwen/Qwen3.5-9B",
                    "steering_coefficient": c,
                    "source": "steered",
                },
            })
            print(f"  [{i+1}/{len(plan)}] {state}/coef={c} "
                  f"({len(generated)} chars): {generated[:60]!r}...",
                  flush=True)
        finally:
            for h in handles:
                h.remove()

    # Append-mode if file exists (combine with refs); but we write a new
    # *_steered.jsonl so refs and steered are kept separable until SSD
    # build_balanced_corpus aggregates.
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as f:
        for ex in examples:
            f.write(json.dumps(ex, ensure_ascii=False) + "\n")
    print(f"  wrote {len(examples)} steered turns to {out_path} "
          f"({skipped_garbage} skipped as garbage)", flush=True)
    return len(examples)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--model", default="Qwen/Qwen3.5-9B")
    p.add_argument("--vectors-dir", required=True)
    p.add_argument("--refs-dir", required=True)
    p.add_argument("--out-dir", required=True)
    p.add_argument("--turns-per-tank", type=int, default=22)
    p.add_argument("--tank", default=None,
                   help="Generate for one tank/drive only (default: all Tier 1)")
    args = p.parse_args()

    print(f"Loading {args.model}...", flush=True)
    from transformers import AutoModelForCausalLM, AutoTokenizer
    import torch
    tokenizer = AutoTokenizer.from_pretrained(args.model)
    model = AutoModelForCausalLM.from_pretrained(
        args.model, torch_dtype=torch.float16, device_map="cuda")
    model.eval()

    # Tier 1 tanks + drives
    targets = {**{k: v for k, v in TANKS.items() if v.get("tier") == 1},
               **DRIVES}
    if args.tank:
        if args.tank not in targets:
            print(f"ERROR: unknown Tier 1 target '{args.tank}'. Known: "
                  f"{sorted(targets)}", file=sys.stderr)
            sys.exit(2)
        targets = {args.tank: targets[args.tank]}

    refs_dir = Path(args.refs_dir)
    vectors_dir = Path(args.vectors_dir)
    out_dir = Path(args.out_dir)

    total = 0
    for name, cfg in targets.items():
        print(f"\n=== {name} ({cfg['tier']}) ===")
        n = generate_steered_for_tank(
            model, tokenizer, cfg, vectors_dir, refs_dir,
            args.turns_per_tank, out_dir / f"{name}_steered.jsonl")
        total += n

    print(f"\nDone. {total} total steered turns generated.")


if __name__ == "__main__":
    main()
