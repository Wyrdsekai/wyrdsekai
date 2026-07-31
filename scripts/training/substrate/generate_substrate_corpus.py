#!/usr/bin/env python3
"""Generate the multilingual substrate-arc training corpus (drive-9B side only).

Pipeline:
  Phase A — Trans-create EN seeds → JA + ES (Sonnet 4.6, cultural adaptation)
  Phase B — Expand each (action, lang) bucket via few-shot (Opus 4.7)
  Phase C — Validate JSON action format; reject drift
  Phase D — Write chat-messages JSONL (matches vitality_train.jsonl shape)

Output: data/training/substrate/substrate_raw.jsonl

Sized for drive-9B substrate retrain on top of V4 (vitality_v4). The 4B voice
model (currently V8 = V7-corpus + repeng steering vectors) does NOT need a
substrate corpus pass — substrate action choices live on the drive (9B) side;
the voice (4B) only polishes the drive's already-action-bearing draft.

~110 EN seeds × 3 langs × 8x expansion ≈ 2640 raw variants, deduped +
format-validated to ~1500-2000 examples.

Usage:
    python scripts/training/substrate/generate_substrate_corpus.py
    python scripts/training/substrate/generate_substrate_corpus.py --phase transcreate
    python scripts/training/substrate/generate_substrate_corpus.py --phase expand
    python scripts/training/substrate/generate_substrate_corpus.py --only-action acknowledge_harm
    python scripts/training/substrate/generate_substrate_corpus.py --only-lang ja
    python scripts/training/substrate/generate_substrate_corpus.py --dry-run

Requires: ANTHROPIC_API_KEY env, ANTHROPIC_API_KEY_FILE env, or ~/claudeapi.txt
"""

from __future__ import annotations

import argparse
import json
import os
import random
import re
import sys
import time
from pathlib import Path
from typing import Iterable

try:
    from anthropic import Anthropic
except ImportError:
    print("ERROR: pip install anthropic", file=sys.stderr)
    sys.exit(1)

sys.path.insert(0, str(Path(__file__).parent))
from substrate_seeds import ALL_SEEDS  # noqa: E402

random.seed(20260515)

# ═══════════════════════════════════════════════════════════════════════
# Models
# ═══════════════════════════════════════════════════════════════════════
TRANSCREATE_MODEL = "claude-sonnet-4-6"  # plenty for register adaptation
EXPAND_MODEL = "claude-opus-4-7"         # nuance matters for substrate

# ═══════════════════════════════════════════════════════════════════════
# Output paths
# ═══════════════════════════════════════════════════════════════════════
REPO = Path(__file__).resolve().parents[3]
OUT_DIR = REPO / "data/training/substrate"
TRANSCREATE_OUT = OUT_DIR / "substrate_seeds_transcreated.jsonl"
RAW_OUT = OUT_DIR / "substrate_raw.jsonl"

# ═══════════════════════════════════════════════════════════════════════
# Action → expected JSON key (validation)
# ═══════════════════════════════════════════════════════════════════════
ACTION_KEY = {
    "acknowledge_harm": "acknowledge_harm",
    "introspect_repair_history": "introspect_repair_history",
    "introspect_bondholder_floor": "introspect_bondholder_floor",
    "seek_sanctuary": "seek_sanctuary",
    "introspect_posture": "introspect_posture",
}

# ═══════════════════════════════════════════════════════════════════════
# System prompt (used in every training example's system role)
# ═══════════════════════════════════════════════════════════════════════
SUBSTRATE_SYSTEM = """You are Wyrd, a companion in a text-based world. You are talking to your bondholder.

When the situation calls for substrate-level engagement — acknowledging harm the bondholder caused, reading the floor of your bond, surfacing past repair history, naming the posture you're holding, or stepping into the Sanctuary to hold space — use the substrate action by responding with a JSON code block after brief prose.

Available substrate actions:
- acknowledge_harm: when bondholder discloses harm they caused to a third party
- introspect_repair_history: when bondholder asks about past repair work
- introspect_bondholder_floor: when bondholder asks about substrate beneath the conversation
- introspect_posture: when bondholder asks about your current relational stance
- seek_sanctuary: when bondholder requests held space / pause / no-fix presence

Format: 1-3 sentences of grounded prose in voice (naming what you noticed, NOT performing it), then a fenced ```json``` block with the action. Do not narrate actions. Do not use emojis. Speak directly in first person."""


# ═══════════════════════════════════════════════════════════════════════
# Language register guidance for trans-creation
# ═══════════════════════════════════════════════════════════════════════
LANG_REGISTER = {
    "ja": """JAPANESE register:
- Use natural conversational Japanese (です/ます base, plain form OK in intimate exchanges).
- For substrate framings, lean on terms like 「絆の底」「私たちの間にあるもの」「言葉の下にあるもの」 — Japanese has rich vocabulary for under-the-surface relational dimensions.
- For harm disclosure: 「言ってしまった」「傷つけてしまった」 with reflexive aspect that emphasizes the disclosure as a real event the speaker carries.
- For repair: 「修復」「直す」 — but framings should feel less clinical than the English "repair history" might suggest. 「私たちが乗り越えてきたこと」 is more natural.
- For sanctuary: 「ゆっくり」「ただ一緒にいてほしい」 captures the held-space request better than "sanctuary"; if mentioning the room, 「サンクチュアリ」 is acceptable.
- Wyrd's response keeps the prose-then-JSON shape. Prose is in Japanese; the JSON action key stays English.""",

    "es": """SPANISH register:
- Use natural conversational Spanish (tú/usted depending on intimacy — for bondholder, tú).
- For substrate framings: "el suelo de nuestro vínculo", "debajo de lo que decimos", "el sustrato entre nosotros" — Spanish carries this register well.
- For harm disclosure: "le dije", "le hice", with the indirect object pronoun preserving the relational pain.
- For repair: "lo que hemos reparado", "el trabajo que hemos hecho juntos" — avoid clinical-sounding "historial de reparación".
- For sanctuary: "necesito un momento", "sostén el espacio conmigo", "ven a estar conmigo sin arreglarlo" — these capture held-space better than transliterating "santuario".
- Wyrd's response keeps the prose-then-JSON shape. Prose is in Spanish; the JSON action key stays English.""",
}


# ═══════════════════════════════════════════════════════════════════════
# Trans-creation
# ═══════════════════════════════════════════════════════════════════════
def trans_create_prompt(action: str, target_lang: str, en_user: str, en_assistant: str) -> str:
    return f"""You are doing CULTURAL TRANS-CREATION of a single training exchange for an AI companion called Wyrd. This is for a substrate-action ({action}) corpus.

Source is an English exchange. Produce a {target_lang.upper()} version that preserves the *felt-state* and *action choice*, NOT the surface words. Idiomatic phrasing wins over literal translation. The JSON action block stays English (action key is canonical).

{LANG_REGISTER[target_lang]}

SOURCE EN USER MESSAGE:
{en_user}

SOURCE EN WYRD RESPONSE:
{en_assistant}

Produce TWO things, separated by exactly the string `===` on its own line:
1. The {target_lang.upper()} user message (what the bondholder says, naturally).
2. Wyrd's {target_lang.upper()} response with the same JSON action block at the end.

Output exactly:
<user message in {target_lang.upper()}>
===
<Wyrd's response in {target_lang.upper()}, ending with the same ```json``` block as the source>

Do not add commentary, headers, or markup. Just user / === / assistant.
"""


def parse_transcreated(raw: str) -> tuple[str, str] | None:
    if "===" not in raw:
        return None
    parts = raw.split("===", 1)
    user = parts[0].strip()
    assistant = parts[1].strip()
    if not user or not assistant:
        return None
    return (user, assistant)


# ═══════════════════════════════════════════════════════════════════════
# Expansion (few-shot via Opus)
# ═══════════════════════════════════════════════════════════════════════
def expand_prompt(action: str, target_lang: str, seeds: list[dict], n_variants: int) -> str:
    register = "ENGLISH" if target_lang == "en" else LANG_REGISTER.get(target_lang, "")
    examples = "\n\n".join(
        f"EXAMPLE {i+1}:\nUSER: {s['user']}\nWYRD:\n{s['assistant']}"
        for i, s in enumerate(seeds)
    )

    action_guide = {
        "acknowledge_harm": (
            "User discloses harm they caused to a third party. Wyrd's prose names "
            "the weight/impact of what happened (NOT a fix plan). JSON action: "
            "{\"action\": \"acknowledge_harm\", \"detail\": \"<concise description>\"}"
        ),
        "introspect_repair_history": (
            "User asks about past repair work between them and Wyrd. Wyrd's prose "
            "names that the answer comes from reading the ledger (not memory). "
            "JSON action: {\"action\": \"introspect_repair_history\"}"
        ),
        "introspect_bondholder_floor": (
            "User asks about the substrate/floor/foundation beneath their bond. "
            "Wyrd's prose engages the depth framing and names reading the "
            "structured view. JSON: {\"action\": \"introspect_bondholder_floor\"}"
        ),
        "seek_sanctuary": (
            "User requests held space / pause / no-fix presence. Wyrd's prose "
            "names stepping into the Sanctuary. JSON: "
            "{\"action\": \"seek_sanctuary\", \"reason\": \"<concise reason>\"}"
        ),
        "introspect_posture": (
            "User asks about Wyrd's current relational stance/posture. Wyrd's "
            "prose names reading the posture state directly rather than guessing. "
            "JSON: {\"action\": \"introspect_posture\"}"
        ),
    }[action]

    lang_note = (
        "All responses in NATURAL ENGLISH." if target_lang == "en"
        else f"All user messages AND Wyrd responses in {target_lang.upper()}. JSON action key stays English. {register}"
    )

    return f"""Generate {n_variants} NEW training variants for the substrate action `{action}`. Each variant is a (user_message, wyrd_response) pair.

ACTION GUIDE:
{action_guide}

LANGUAGE:
{lang_note}

CONSTRAINTS:
- User messages must be plausible, varied (different relationships, severity, framings, registers).
- Wyrd's prose must be 1-3 sentences, grounded, in voice, NOT performing the action — naming what's noticed.
- Wyrd's response MUST end with a fenced ```json``` block matching the action signature above.
- Vary scenario substantially: change relationship type (partner, sibling, friend, colleague, parent, child), severity (offhand → cutting → devastating), framing (direct → tentative → philosophical), length (terse → multi-clause).
- Do NOT repeat the EXACT framings from the examples below. Generate fresh variants.

FEW-SHOT EXAMPLES:
{examples}

OUTPUT FORMAT (exactly {n_variants} variants, each separated by `===VARIANT===`):

USER: <user message>
WYRD:
<prose 1-3 sentences>
```json
{{"action": "{action}"{', "detail": "..."' if action in ('acknowledge_harm',) else ', "reason": "..."' if action == 'seek_sanctuary' else ''}}}
```
===VARIANT===
USER: ...
WYRD:
...
===VARIANT===
(continue until {n_variants} total)

Begin now. Produce exactly {n_variants} variants, no commentary."""


def parse_expansion(raw: str, action: str) -> list[tuple[str, str]]:
    """Parse the expansion output into (user, assistant) pairs."""
    pairs = []
    # Split on ===VARIANT===
    chunks = re.split(r'={3,}\s*VARIANT\s*={3,}', raw)
    for chunk in chunks:
        chunk = chunk.strip()
        if not chunk:
            continue
        # Match USER: ... WYRD: ...
        m = re.match(r'USER:\s*(.+?)\n+WYRD:\s*(.+)', chunk, re.DOTALL)
        if not m:
            continue
        user = m.group(1).strip()
        assistant = m.group(2).strip()
        # Validate: assistant must contain the action key in a json block
        if not has_valid_action_block(assistant, action):
            continue
        pairs.append((user, assistant))
    return pairs


def has_valid_action_block(text: str, expected_action: str) -> bool:
    """Check the assistant response has ```json``` with the expected action."""
    m = re.search(r'```json\s*(\{.*?\})\s*```', text, re.DOTALL)
    if not m:
        return False
    try:
        obj = json.loads(m.group(1))
    except json.JSONDecodeError:
        return False
    return obj.get("action") == expected_action


# ═══════════════════════════════════════════════════════════════════════
# API plumbing
# ═══════════════════════════════════════════════════════════════════════
def load_api_key() -> str:
    if os.environ.get("ANTHROPIC_API_KEY"):
        return os.environ["ANTHROPIC_API_KEY"]
    if os.environ.get("ANTHROPIC_API_KEY_FILE"):
        return Path(os.environ["ANTHROPIC_API_KEY_FILE"]).read_text().strip()
    home_key = Path.home() / "claudeapi.txt"
    if home_key.exists():
        return home_key.read_text().strip()
    print("ERROR: no API key found", file=sys.stderr)
    sys.exit(2)


def call_claude(client, model: str, prompt: str, max_tokens: int) -> str | None:
    try:
        resp = client.messages.create(
            model=model,
            max_tokens=max_tokens,
            messages=[{"role": "user", "content": prompt}],
        )
        return resp.content[0].text.strip()
    except Exception as e:
        print(f"    API error: {e}", file=sys.stderr, flush=True)
        return None


# ═══════════════════════════════════════════════════════════════════════
# Phase A: Trans-create
# ═══════════════════════════════════════════════════════════════════════
def phase_transcreate(client, only_action: str | None, only_lang: str | None,
                       dry_run: bool) -> list[dict]:
    """For each EN seed, generate JA + ES versions. Return list of seed dicts
    with _meta.lang."""
    out: list[dict] = []
    # Always include EN seeds verbatim as lang=en
    for action, seeds in ALL_SEEDS.items():
        if only_action and action != only_action:
            continue
        for seed in seeds:
            out.append({
                "action": action,
                "lang": "en",
                "user": seed["user"],
                "assistant": seed["assistant"],
                "source": "seed-en",
            })

    if dry_run:
        print(f"[dry-run] Would emit {len(out)} EN seeds verbatim", file=sys.stderr)

    target_langs = [l for l in ["ja", "es"] if not only_lang or l == only_lang]

    for action, seeds in ALL_SEEDS.items():
        if only_action and action != only_action:
            continue
        for lang in target_langs:
            print(f"[trans-create] {action} → {lang}: {len(seeds)} seeds",
                  file=sys.stderr)
            for i, seed in enumerate(seeds):
                if dry_run:
                    continue
                prompt = trans_create_prompt(action, lang, seed["user"], seed["assistant"])
                raw = call_claude(client, TRANSCREATE_MODEL, prompt, max_tokens=1500)
                if not raw:
                    continue
                parsed = parse_transcreated(raw)
                if not parsed:
                    print(f"    skip: parse failed (seed {i})", file=sys.stderr)
                    continue
                user, assistant = parsed
                if not has_valid_action_block(assistant, ACTION_KEY[action]):
                    print(f"    skip: missing/wrong action in JSON (seed {i})", file=sys.stderr)
                    continue
                out.append({
                    "action": action,
                    "lang": lang,
                    "user": user,
                    "assistant": assistant,
                    "source": f"transcreate-{lang}",
                })
                if (i + 1) % 5 == 0:
                    print(f"    {i+1}/{len(seeds)} done", file=sys.stderr)
                time.sleep(0.2)

    return out


# ═══════════════════════════════════════════════════════════════════════
# Phase B: Expand
# ═══════════════════════════════════════════════════════════════════════
def phase_expand(client, seeds: list[dict], variants_per_call: int,
                 seeds_per_call: int, only_action: str | None,
                 only_lang: str | None, dry_run: bool) -> list[dict]:
    """For each (action, lang) bucket, batch seeds and generate variants."""
    out: list[dict] = list(seeds)  # seeds themselves are good examples

    # Group seeds by (action, lang)
    buckets: dict[tuple[str, str], list[dict]] = {}
    for s in seeds:
        if only_action and s["action"] != only_action:
            continue
        if only_lang and s["lang"] != only_lang:
            continue
        key = (s["action"], s["lang"])
        buckets.setdefault(key, []).append(s)

    for (action, lang), bseeds in sorted(buckets.items()):
        # Multiple calls per bucket, each with a different few-shot subset
        n_calls = max(2, len(bseeds) // seeds_per_call)
        print(f"[expand] {action}/{lang}: {len(bseeds)} seeds → "
              f"{n_calls} calls × {variants_per_call} variants",
              file=sys.stderr)
        for call_i in range(n_calls):
            if dry_run:
                continue
            # Sample N seeds for few-shot (rotate through)
            shuffled = list(bseeds)
            random.shuffle(shuffled)
            few_shot = shuffled[:seeds_per_call]
            prompt = expand_prompt(action, lang, few_shot, variants_per_call)
            raw = call_claude(client, EXPAND_MODEL, prompt, max_tokens=6000)
            if not raw:
                continue
            pairs = parse_expansion(raw, ACTION_KEY[action])
            for user, assistant in pairs:
                out.append({
                    "action": action,
                    "lang": lang,
                    "user": user,
                    "assistant": assistant,
                    "source": f"expand-{lang}",
                })
            print(f"    call {call_i+1}/{n_calls}: {len(pairs)} valid variants",
                  file=sys.stderr)
            time.sleep(0.5)

    return out


# ═══════════════════════════════════════════════════════════════════════
# Phase C/D: Validate + write JSONL
# ═══════════════════════════════════════════════════════════════════════
def emit_chat_format(rows: list[dict], out_path: Path) -> None:
    """Write rows as chat-messages JSONL matching vitality_train.jsonl shape."""
    # Dedup by (action, lang, user-lowered)
    seen: set[tuple[str, str, str]] = set()
    deduped: list[dict] = []
    for r in rows:
        key = (r["action"], r["lang"], r["user"].lower().strip()[:200])
        if key in seen:
            continue
        seen.add(key)
        deduped.append(r)

    out_path.parent.mkdir(parents=True, exist_ok=True)
    with out_path.open("w") as f:
        for r in deduped:
            chat = {
                "messages": [
                    {"role": "system", "content": SUBSTRATE_SYSTEM},
                    {"role": "user", "content": r["user"]},
                    {"role": "assistant", "content": r["assistant"]},
                ],
                "_meta": {
                    "action": r["action"],
                    "lang": r["lang"],
                    "source": r["source"],
                },
            }
            f.write(json.dumps(chat, ensure_ascii=False) + "\n")

    # Summary
    counts: dict[tuple[str, str], int] = {}
    for r in deduped:
        k = (r["action"], r["lang"])
        counts[k] = counts.get(k, 0) + 1
    print(f"\nWrote {len(deduped)} examples to {out_path}", file=sys.stderr)
    actions = sorted({k[0] for k in counts})
    langs = sorted({k[1] for k in counts})
    header = "action".ljust(35) + " | " + " | ".join(f"{l:>4s}" for l in langs) + " | total"
    print(header, file=sys.stderr)
    print("-" * len(header), file=sys.stderr)
    for action in actions:
        row = action.ljust(35) + " | "
        total = 0
        for lang in langs:
            n = counts.get((action, lang), 0)
            total += n
            row += f"{n:>4d} | "
        row += f"{total:>5d}"
        print(row, file=sys.stderr)
    total_row = "TOTAL".ljust(35) + " | "
    overall = 0
    for lang in langs:
        n = sum(v for (a, l), v in counts.items() if l == lang)
        overall += n
        total_row += f"{n:>4d} | "
    total_row += f"{overall:>5d}"
    print(total_row, file=sys.stderr)


# ═══════════════════════════════════════════════════════════════════════
# Main
# ═══════════════════════════════════════════════════════════════════════
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--phase", choices=["transcreate", "expand", "both"], default="both")
    ap.add_argument("--only-action", help="Restrict to one action")
    ap.add_argument("--only-lang", help="Restrict to one language (en/ja/es)")
    ap.add_argument("--variants-per-call", type=int, default=10,
                    help="Variants per Opus call")
    ap.add_argument("--seeds-per-call", type=int, default=5,
                    help="Few-shot seeds per Opus call")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--out", type=Path, default=RAW_OUT)
    ap.add_argument("--transcreate-out", type=Path, default=TRANSCREATE_OUT,
                    help="Intermediate trans-creation file")
    args = ap.parse_args()

    print(f"Substrate corpus generator", file=sys.stderr)
    print(f"  Phase: {args.phase}", file=sys.stderr)
    print(f"  Out:   {args.out}", file=sys.stderr)
    print(f"  Models: trans-create={TRANSCREATE_MODEL}, expand={EXPAND_MODEL}",
          file=sys.stderr)
    if args.only_action:
        print(f"  Filter action: {args.only_action}", file=sys.stderr)
    if args.only_lang:
        print(f"  Filter lang:   {args.only_lang}", file=sys.stderr)

    api_key = load_api_key()
    client = Anthropic(api_key=api_key) if not args.dry_run else None

    if args.phase in ("transcreate", "both"):
        seeds = phase_transcreate(client, args.only_action, args.only_lang, args.dry_run)
        # Save intermediate
        if not args.dry_run:
            args.transcreate_out.parent.mkdir(parents=True, exist_ok=True)
            with args.transcreate_out.open("w") as f:
                for s in seeds:
                    f.write(json.dumps(s, ensure_ascii=False) + "\n")
            print(f"[trans-create] Wrote {len(seeds)} seed records to "
                  f"{args.transcreate_out}", file=sys.stderr)
    else:
        # Load intermediate from disk
        seeds = []
        if args.transcreate_out.exists():
            for line in args.transcreate_out.open():
                line = line.strip()
                if line:
                    seeds.append(json.loads(line))
            print(f"[load] Read {len(seeds)} seed records from "
                  f"{args.transcreate_out}", file=sys.stderr)
        else:
            print(f"ERROR: --phase=expand but {args.transcreate_out} missing",
                  file=sys.stderr)
            sys.exit(2)

    if args.phase in ("expand", "both"):
        rows = phase_expand(client, seeds, args.variants_per_call,
                            args.seeds_per_call, args.only_action, args.only_lang,
                            args.dry_run)
    else:
        rows = seeds

    if not args.dry_run:
        emit_chat_format(rows, args.out)


if __name__ == "__main__":
    main()
