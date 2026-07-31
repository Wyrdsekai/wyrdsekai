#!/usr/bin/env python3
"""Batch-API variant of generate_react_corpus.py — 50% cheaper.

Same patterns + seeds + validation + output shape as generate_react_corpus.py,
but submits the entire (pattern × prompt × lang × variant) plan as ONE
`client.messages.batches.create(...)` call instead of running sync calls in
parallel workers. Polls until completed, then streams results into JSONL.

Cost: Anthropic Message Batches API is 50% the price of sync messages.create
for the same model + token count. For substrate-v2 corpus this would have
been ~$15 instead of ~$30.

Trans-creation (EN→JA/ES) still uses sync calls — only 15 batches of 10
prompts each, low volume, batch overhead not worth it. Trans-creation phase
shares the existing OUT path so this script reads `react_user_prompts_transcreated.jsonl`.

Usage:
    # First: run trans-creation (small) via the sync script:
    python scripts/training/substrate/generate_react_corpus.py --phase transcreate

    # Then: generate the full multi-turn ReAct corpus via batch:
    python scripts/training/substrate/generate_react_corpus_batch.py --variants 3

    # Smaller smoke run:
    python scripts/training/substrate/generate_react_corpus_batch.py \\
        --variants 1 --only-pattern p2_library_empty_web_fallback

Requires: ANTHROPIC_API_KEY env, ANTHROPIC_API_KEY_FILE env, or ~/claudeapi.txt

Limits:
- Up to 100k requests per batch (we use ~1300)
- 24h max processing time (typically <1h)
- Cannot interrupt mid-batch — all-or-nothing per submission
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import time
from pathlib import Path

try:
    from anthropic import Anthropic
except ImportError:
    print("ERROR: pip install anthropic", file=sys.stderr)
    sys.exit(1)

sys.path.insert(0, str(Path(__file__).parent))
from react_task_patterns import PATTERNS  # noqa: E402
from generate_react_corpus import (  # noqa: E402
    GEN_MODEL,
    WYRDSEKAI_SYSTEM,
    GEN_PROMPT_TEMPLATE,
    PATTERN_SPECIAL_NOTES,
    LANG_DIRECTIVES,
    TRANSCREATE_OUT,
    OUT_DIR,
    load_api_key,
    extract_json,
    validate_trace,
    trace_to_messages,
)

DEFAULT_OUT = OUT_DIR / "react_preservation_batch.jsonl"


def build_requests(only_pattern: str | None, only_langs: tuple[str, ...],
                   variants_per: int, max_per_pattern: int | None,
                   transcreations: dict[tuple[str, str], dict[str, str]]
                   ) -> list[dict]:
    """Enumerate every (pattern, prompt, lang, variant) cell into batch requests.

    custom_id format: `<pattern_id>__<lang>__<prompt_idx>__v<variant>`
    """
    requests = []
    for pattern in PATTERNS:
        if only_pattern and pattern["id"] != only_pattern:
            continue
        prompts = pattern["en_prompts"]
        if max_per_pattern:
            prompts = prompts[:max_per_pattern]
        for prompt_idx, en_prompt in enumerate(prompts):
            tr = transcreations.get((pattern["id"], en_prompt))
            for lang in only_langs:
                user_prompt = en_prompt if lang == "en" else (tr.get(lang) if tr else None)
                if not user_prompt:
                    continue
                special = PATTERN_SPECIAL_NOTES.get(pattern["id"],
                    "Follow the target tool sequence and produce a useful concrete answer.")
                prompt_text = GEN_PROMPT_TEMPLATE.format(
                    pattern_id=pattern["id"],
                    pattern_description=pattern["description"],
                    pattern_tools=" → ".join(pattern["tools"]),
                    user_prompt=user_prompt,
                    pattern_special_note=special,
                    lang_directive=LANG_DIRECTIVES[lang],
                )
                for v in range(variants_per):
                    custom_id = f"{pattern['id']}__{lang}__{prompt_idx}__v{v}"
                    requests.append({
                        "custom_id": custom_id,
                        "params": {
                            "model": GEN_MODEL,
                            "max_tokens": 2048,
                            "system": WYRDSEKAI_SYSTEM,
                            "messages": [{"role": "user", "content": prompt_text}],
                        },
                    })
    return requests


def parse_custom_id(custom_id: str) -> tuple[str, str, int, int]:
    """Inverse of the custom_id format. Returns (pattern_id, lang, prompt_idx, variant)."""
    m = re.match(r"(?P<pid>.+)__(?P<lang>en|ja|es)__(?P<idx>\d+)__v(?P<v>\d+)$", custom_id)
    if not m:
        raise ValueError(f"unparseable custom_id: {custom_id}")
    return m["pid"], m["lang"], int(m["idx"]), int(m["v"])


def load_transcreations() -> dict[tuple[str, str], dict[str, str]]:
    out: dict[tuple[str, str], dict[str, str]] = {}
    if not TRANSCREATE_OUT.exists():
        return out
    with TRANSCREATE_OUT.open() as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            r = json.loads(line)
            out[(r["pattern_id"], r["en"])] = r
    return out


def lookup_prompt(pattern_id: str, lang: str, prompt_idx: int,
                  transcreations: dict[tuple[str, str], dict[str, str]]) -> str | None:
    """Rebuild the user_prompt for a custom_id so we can include it in the chat-messages record."""
    for p in PATTERNS:
        if p["id"] == pattern_id:
            if prompt_idx >= len(p["en_prompts"]):
                return None
            en = p["en_prompts"][prompt_idx]
            if lang == "en":
                return en
            tr = transcreations.get((pattern_id, en))
            return tr.get(lang) if tr else None
    return None


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--variants", type=int, default=1,
                    help="Variants per (pattern, prompt, lang) cell")
    ap.add_argument("--only-pattern", default=None,
                    help="Limit to one pattern id")
    ap.add_argument("--only-lang", default=None, choices=["en", "ja", "es"],
                    help="Limit to one language")
    ap.add_argument("--max-per-pattern", type=int, default=None,
                    help="Cap EN prompts per pattern (smoke)")
    ap.add_argument("--out", type=Path, default=DEFAULT_OUT,
                    help="Output JSONL path")
    ap.add_argument("--batch-id", default=None,
                    help="Skip submit, retrieve+process this existing batch id")
    ap.add_argument("--dry-run", action="store_true",
                    help="Print plan + estimated cost, don't submit")
    ap.add_argument("--poll-seconds", type=int, default=30,
                    help="Seconds between status polls (default 30)")
    args = ap.parse_args()

    langs = (args.only_lang,) if args.only_lang else ("en", "ja", "es")

    OUT_DIR.mkdir(parents=True, exist_ok=True)

    # ── Build / load batch ────────────────────────────────────────────────
    transcreations = load_transcreations()
    if transcreations:
        print(f"Loaded {len(transcreations)} trans-creations from {TRANSCREATE_OUT}")
    else:
        print(f"NOTE: {TRANSCREATE_OUT} not found — EN-only requests will be built")

    if args.batch_id:
        client = Anthropic(api_key=load_api_key())
        batch_id = args.batch_id
        print(f"Skipping submit. Will retrieve batch {batch_id}.")
    else:
        requests = build_requests(
            args.only_pattern, langs, args.variants, args.max_per_pattern, transcreations
        )
        print(f"Built {len(requests)} batch requests "
              f"({len(PATTERNS) if not args.only_pattern else 1} patterns × "
              f"{args.variants} variants × {len(langs)} langs)")
        # Cost estimate: ~2k in + ~1k out per request, batch is 50% sync price
        # Sonnet 4.6 sync: $3/$15 per MTok → batch $1.50/$7.50
        ein_mtok = sum(r["params"]["max_tokens"] for r in requests) * 0  # placeholder
        # cleaner upper bound:
        n = len(requests)
        in_cost = n * 2000 / 1_000_000 * 1.50
        out_cost = n * 1000 / 1_000_000 * 7.50
        print(f"Estimated batch cost: ~${in_cost:.2f} input + ${out_cost:.2f} output = ${in_cost + out_cost:.2f}")
        if args.dry_run:
            return

        client = Anthropic(api_key=load_api_key())
        print(f"\nSubmitting batch with {n} requests…")
        batch = client.messages.batches.create(requests=requests)
        batch_id = batch.id
        print(f"Batch ID: {batch_id}")
        print(f"Status:  {batch.processing_status}")
        # Persist batch id so we can resume retrieval after crash
        (OUT_DIR / "last_batch_id.txt").write_text(batch_id + "\n")

    # ── Poll ───────────────────────────────────────────────────────────────
    print(f"\nPolling every {args.poll_seconds}s. Batch can take up to 24h; "
          f"smaller batches usually finish in minutes.")
    while True:
        status = client.messages.batches.retrieve(batch_id)
        counts = status.request_counts
        print(f"  [{time.strftime('%H:%M:%S')}] processing_status={status.processing_status} "
              f"succeeded={counts.succeeded} processing={counts.processing} "
              f"errored={counts.errored} canceled={getattr(counts, 'canceled', 0)} "
              f"expired={getattr(counts, 'expired', 0)}", flush=True)
        if status.processing_status == "ended":
            break
        time.sleep(args.poll_seconds)

    # ── Retrieve + validate + write ────────────────────────────────────────
    print(f"\nRetrieving results…")
    written = 0
    rejected_parse = 0
    rejected_validate = 0
    errored = 0
    out_f = args.out.open("w")
    try:
        for result in client.messages.batches.results(batch_id):
            if result.result.type != "succeeded":
                errored += 1
                err = getattr(result.result, "error", None)
                if err:
                    print(f"  [error] {result.custom_id}: "
                          f"{getattr(err, 'type', '?')}: {getattr(err, 'message', '?')[:120]}",
                          file=sys.stderr)
                continue
            text = result.result.message.content[0].text
            obj = extract_json(text)
            if obj is None:
                rejected_parse += 1
                continue
            trace = obj.get("trace")
            if not isinstance(trace, list) or len(trace) < 1:
                rejected_parse += 1
                continue
            try:
                pattern_id, lang, prompt_idx, variant = parse_custom_id(result.custom_id)
            except ValueError:
                rejected_parse += 1
                continue
            ok, reason = validate_trace(trace, pattern_id)
            if not ok:
                rejected_validate += 1
                continue
            user_prompt = lookup_prompt(pattern_id, lang, prompt_idx, transcreations)
            if not user_prompt:
                rejected_validate += 1
                continue
            messages = trace_to_messages(user_prompt, trace)
            record = {
                "messages": messages,
                "_meta": {
                    "pattern_id": pattern_id,
                    "lang": lang,
                    "variant": variant,
                    "source": "react_preservation_batch_v1",
                    "batch_id": batch_id,
                },
            }
            out_f.write(json.dumps(record, ensure_ascii=False) + "\n")
            written += 1
    finally:
        out_f.close()

    print()
    print(f"=== DONE ===")
    print(f"  written:           {written}")
    print(f"  rejected (parse):  {rejected_parse}")
    print(f"  rejected (valid):  {rejected_validate}")
    print(f"  errored:           {errored}")
    print(f"  output:            {args.out}")


if __name__ == "__main__":
    main()
