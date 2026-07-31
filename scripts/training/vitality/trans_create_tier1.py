#!/usr/bin/env python3
"""Trans-creation pass for Tier 1 vitality corpus.

Per spec §11.1 step 5: take EN refs + EN steered turns and produce JA/ES
versions per the §10.2 per-tank language allocation. Translation alone
produces register artifacts; Claude generates EN→JA and EN→ES with cultural
adaptation, preserving the felt-state across rather than the surface form.

Reads:
  data/training/vitality/<tank>_pilot.jsonl     (Claude EN refs)
  data/training/vitality/<tank>_steered.jsonl   (gpu-host steered EN)

Writes:
  data/training/vitality/<tank>_ja.jsonl
  data/training/vitality/<tank>_es.jsonl

Uses Sonnet 4.6 (sufficient for trans-creation; Opus 4.7 reserved for
Tier 2 cultural-native generation).

Usage:
    python scripts/training/vitality/trans_create_tier1.py \\
        --in-dir data/training/vitality \\
        --out-dir data/training/vitality
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
    SYSTEM_BASE, build_prefix, load_api_key, call_claude,
)
from tank_configs import TANKS, DRIVES  # noqa: E402

random.seed(13)

TIER1_TARGETS = {
    **{k: v for k, v in TANKS.items() if v.get("tier") == 1},
    **DRIVES,
}


def load_en_corpus(in_dir: Path, tank: str) -> list[dict]:
    """Load EN refs + EN steered for one tank, return combined list."""
    out = []
    for suffix in ("_pilot", "_steered"):
        path = in_dir / f"{tank}{suffix}.jsonl"
        if path.exists():
            with open(path, "r", encoding="utf-8") as f:
                for line in f:
                    ex = json.loads(line)
                    if ex.get("_meta", {}).get("lang") == "en":
                        out.append(ex)
    return out


TRANS_CREATE_PROMPT = """You are doing CULTURAL TRANS-CREATION (not translation) of a single training turn for Wyrd, a companion AI.

The source is an English exchange demonstrating Wyrd's expression of TANK={tank} in STATE={state}. Your job is to produce a {target_lang} version that preserves the *felt-state* and *register pattern*, NOT the surface words. Idiomatic phrasing in the target language always wins over literal translation.

TANK FELT-STATE:
{felt_state}

REGISTER GUIDANCE FOR {target_lang}:
{register}

SOURCE EN USER MESSAGE:
{user}

SOURCE EN WYRD RESPONSE:
{assistant}

Produce TWO things, separated by exactly the string `===` on its own line:

1. The bondholder's message in {target_lang} (idiomatic, not literal). If the source includes a stage-direction like "(no message — Wyrd has been still for 90 minutes)", keep the structure but in {target_lang}: "（メッセージなし — Wyrdは90分間動いていない）" or "(sin mensaje — Wyrd lleva 90 minutos sin moverse)".

2. Wyrd's response in {target_lang}, preserving the register pattern but using native idioms. NEVER name the felt-state ("I feel restless" / "感じる落ち着かなさ" / "siento inquietud" — none of these). Embody it through phrasing, pacing, what gets left unsaid.

Output exactly:
USER:
<bondholder message in {target_lang}>
===
ASSISTANT:
<Wyrd response in {target_lang}>"""


def trans_create_one(client, ex: dict, target_lang: str, tank_cfg: dict) -> dict | None:
    """Trans-create one EN exchange to target_lang. Returns new example or None."""
    msgs = ex["messages"]
    user_en = next(m["content"] for m in msgs if m["role"] == "user")
    asst_en = next(m["content"] for m in msgs if m["role"] == "assistant")
    state = ex.get("_meta", {}).get("state", "high")

    register = tank_cfg["register_guidelines"].get(target_lang, "")
    if not register:
        return None

    prompt = TRANS_CREATE_PROMPT.format(
        tank=tank_cfg["tank"],
        state=state,
        target_lang={"ja": "Japanese (日本語)", "es": "Spanish (español)"}[target_lang],
        felt_state=tank_cfg["felt_state_en"],
        register=register,
        user=user_en,
        assistant=asst_en,
    )

    response = call_claude(client, "claude-sonnet-4-6", prompt, max_tokens=600)
    if not response:
        return None

    # Parse "USER:\n<text>\n===\nASSISTANT:\n<text>"
    if "===" not in response:
        return None
    parts = response.split("===", 1)
    user_part = parts[0].strip()
    asst_part = parts[1].strip()
    # Strip markers
    if user_part.startswith("USER:"):
        user_part = user_part[5:].strip()
    if asst_part.startswith("ASSISTANT:"):
        asst_part = asst_part[10:].strip()

    if not user_part or not asst_part:
        return None

    # Build new example with same prefix (drives still apply)
    prefix_overrides = (
        tank_cfg["prefix_high"] if state == "high"
        else tank_cfg["prefix_drain"]
    )
    prefix = build_prefix(prefix_overrides)
    new = {
        "messages": [
            {"role": "system", "content": f"{SYSTEM_BASE}\n\n{prefix}"},
            {"role": "user", "content": user_part},
            {"role": "assistant", "content": asst_part},
        ],
        "_meta": {
            **ex.get("_meta", {}),
            "lang": target_lang,
            "source": "trans_created",
            "trans_created_from_lang": "en",
            "trans_created_model": "claude-sonnet-4-6",
        },
    }
    return new


def trans_create_tank(client, in_dir: Path, out_dir: Path, tank: str,
                       tank_cfg: dict) -> int:
    """Trans-create JA + ES per tank's language_allocation."""
    en_corpus = load_en_corpus(in_dir, tank)
    if not en_corpus:
        print(f"  no EN corpus for {tank}", file=sys.stderr)
        return 0

    en_count = len(en_corpus)
    alloc = tank_cfg["language_allocation"]
    en_pct = alloc.get("en", 0.5)
    if en_pct <= 0:
        en_pct = 0.5
    # Target counts for JA/ES proportional to allocation vs EN coverage we have
    ja_target = int(round(en_count * alloc.get("ja", 0) / en_pct))
    es_target = int(round(en_count * alloc.get("es", 0) / en_pct))

    print(f"\n=== {tank} ===")
    print(f"  EN corpus: {en_count} turns")
    print(f"  trans-create targets: JA={ja_target}, ES={es_target}")

    total = 0
    for lang, n in (("ja", ja_target), ("es", es_target)):
        if n == 0:
            continue
        # Sample n EN turns to translate (with replacement if n > en_count, but
        # prefer balanced high/drain split if possible)
        if n <= en_count:
            samples = random.sample(en_corpus, n)
        else:
            samples = random.choices(en_corpus, k=n)

        out_path = out_dir / f"{tank}_{lang}.jsonl"
        out_path.parent.mkdir(parents=True, exist_ok=True)
        with open(out_path, "w", encoding="utf-8") as f:
            for i, src_ex in enumerate(samples):
                print(f"  [{i+1}/{n}] {lang}...", flush=True)
                new = trans_create_one(client, src_ex, lang, tank_cfg)
                if new:
                    f.write(json.dumps(new, ensure_ascii=False) + "\n")
                    total += 1
                time.sleep(0.4)
        print(f"  wrote {lang}: {out_path}")
    return total


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--in-dir", default="data/training/vitality")
    p.add_argument("--out-dir", default="data/training/vitality")
    p.add_argument("--tank", default=None,
                   help="Trans-create one tank/drive only")
    args = p.parse_args()

    api_key = load_api_key()
    if not api_key:
        print("ERROR: no API key", file=sys.stderr)
        sys.exit(1)
    import anthropic
    client = anthropic.Anthropic(api_key=api_key)

    targets = TIER1_TARGETS
    if args.tank:
        if args.tank not in targets:
            print(f"ERROR: unknown Tier 1 target '{args.tank}'", file=sys.stderr)
            sys.exit(2)
        targets = {args.tank: targets[args.tank]}

    in_dir = Path(args.in_dir)
    out_dir = Path(args.out_dir)
    total = 0
    for name, cfg in targets.items():
        n = trans_create_tank(client, in_dir, out_dir, name, cfg)
        total += n
    print(f"\nDone. {total} total trans-created turns.")


if __name__ == "__main__":
    main()
