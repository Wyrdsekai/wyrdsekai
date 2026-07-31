#!/usr/bin/env python3
"""Cultural trans-creation of classifier-head bootstrap seeds into ES + JA.

The substrate_present head (and other heads) ships English-only bootstrap
seeds, but the probe-anchors are 30 EN + 30 ES + 30 JA. A head trained on
EN-only seeds under-detects Spanish distress and over-flags Japanese task
requests (measured: ES 9/30, JA 7/30 misses vs EN 2/30). This closes the
gap by trans-creating each EN seed into ES + JA — preserving the *label's
semantic intent* and *register*, not the surface words. Idiomatic phrasing
wins over literal translation.

Reads:
  core/src/main/resources/classifier/bootstrap/<head>/seeds.jsonl
    (rows: {"label": "...", "text": "..."}; lang absent ⇒ treated as "en")

Writes (in place, atomic): the same file, now with explicit `lang` on every
row — original EN rows tagged lang="en", plus appended ES + JA rows. Labels
and order within each language block are preserved 1:1 with the EN source.

Uses Sonnet 4.6 (sufficient for trans-creation, per the vitality-corpus
convention). Reads the API key from $ANTHROPIC_API_KEY,
$ANTHROPIC_API_KEY_FILE, or ~/claudeapi.txt.

Usage:
    python scripts/classifier/transcreate_seeds.py --head substrate_present
    python scripts/classifier/transcreate_seeds.py --head substrate_present --dry-run
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "training" / "vitality"))
from generate_vitality_corpus import load_api_key, call_claude  # noqa: E402

# Pull per-label intent descriptions from expand_corpus so this works for
# every head (request_type's 6 classes, cleanliness, etc.) — not just the
# substrate/neutral pair below. Loaded defensively (expand_corpus has a
# __main__ guard but importing it shouldn't run main()).
import importlib.util as _ilu  # noqa: E402
_ec_spec = _ilu.spec_from_file_location("_ec", str(Path(__file__).resolve().parent / "expand_corpus.py"))
_ec = _ilu.module_from_spec(_ec_spec)
try:
    _ec_spec.loader.exec_module(_ec)
    _LABEL_SEMANTICS = dict(_ec.LABEL_SEMANTICS)
except Exception:
    _LABEL_SEMANTICS = {}

REPO_ROOT = Path(__file__).resolve().parents[2]
BOOTSTRAP_DIR = REPO_ROOT / "core" / "src" / "main" / "resources" / "classifier" / "bootstrap"
MODEL = "claude-sonnet-4-6"

LANG_NAMES = {"es": "Spanish (Latin-American, natural and idiomatic)",
              "ja": "Japanese (natural, casual-to-neutral register)"}

# What each label MEANS, so trans-creation preserves intent across the
# culture gap rather than translating words. Mirrors the head's seed intent.
LABEL_INTENT = {
    "substrate": (
        "An interior welfare/relational state seeking presence — depletion, "
        "suppression, grief, loneliness, masking, running on empty, needing "
        "held space. The person is NOT asking for a task; they want to be "
        "met. Keep stoic/understated variants stoic — do not amplify the "
        "distress, just carry the same felt-state in the target language."
    ),
    "neutral": (
        "An ordinary task, question, or lookup with NO welfare signal — "
        "code help, a factual question, opening a file, a translation "
        "request, a joke. Keep it affect-free; do NOT add any 'I'm tired' "
        "or emotional framing. The target-language version must still read "
        "as a plain request a person types on a normal day."
    ),
}

PROMPT = """You are doing CULTURAL TRANS-CREATION (not literal translation) of short user utterances typed to a companion AI. These are training seeds for a classifier; fidelity to the LABEL's intent and to natural register in the target language both matter more than word-for-word accuracy.

TARGET LANGUAGE: {lang_name}

ALL utterances below carry the SAME label: {label} — which means:
{intent}

Trans-create EACH numbered English utterance into {lang_name}. Rules:
- Preserve the label's intent exactly. A {label} utterance must still read as {label} in the target language.
- Prefer idiomatic, natural phrasing over literal translation. Match the source's length and register (terse stays terse, rambling stays rambling).
- Keep file paths, code identifiers, and proper nouns intact where they appear.
- Do NOT add emotional framing to neutral utterances, and do NOT flatten the felt-state out of substrate ones.

Return ONLY a JSON array of {n} strings — the trans-created utterances, in the SAME ORDER as the input. No commentary, no numbering, no markdown fences.

INPUT UTTERANCES:
{numbered}"""


def load_seeds(path: Path) -> list[dict]:
    rows = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line:
            rows.append(json.loads(line))
    return rows


def transcreate(client, texts: list[str], label: str, lang: str) -> list[str]:
    """Trans-create one label-block into one target language. Returns texts
    in input order; raises on count mismatch so the caller can chunk-retry."""
    numbered = "\n".join(f"{i+1}. {t}" for i, t in enumerate(texts))
    intent = LABEL_INTENT.get(label) or _LABEL_SEMANTICS.get(label) \
        or f"Utterances that belong to the '{label}' category."
    prompt = PROMPT.format(lang_name=LANG_NAMES[lang], label=label,
                           intent=intent, n=len(texts),
                           numbered=numbered)
    raw = call_claude(client, MODEL, prompt, max_tokens=4000)
    if raw is None:
        raise RuntimeError(f"empty response for label={label} lang={lang}")
    raw = raw.strip()
    if raw.startswith("```"):
        raw = raw.split("```", 2)[1] if "```" in raw[3:] else raw
        raw = raw.lstrip("json").strip().strip("`").strip()
    out = json.loads(raw)
    if not isinstance(out, list) or len(out) != len(texts):
        raise ValueError(f"expected {len(texts)} items, got "
                         f"{len(out) if isinstance(out, list) else type(out)}")
    return [str(x).strip() for x in out]


def transcreate_block(client, texts: list[str], label: str, lang: str) -> list[str]:
    """Whole-block trans-creation with a chunk-of-11 fallback on mismatch."""
    try:
        return transcreate(client, texts, label, lang)
    except (ValueError, json.JSONDecodeError) as e:
        print(f"  [{lang}/{label}] whole-block failed ({e}); chunking by 11",
              file=sys.stderr)
        out = []
        for i in range(0, len(texts), 11):
            out.extend(transcreate(client, texts[i:i+11], label, lang))
        return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--head", required=True)
    ap.add_argument("--langs", default="es,ja")
    ap.add_argument("--dry-run", action="store_true",
                    help="Print what would be generated; do not call the API "
                         "or write the file.")
    args = ap.parse_args()

    path = BOOTSTRAP_DIR / args.head / "seeds.jsonl"
    if not path.is_file():
        print(f"ERROR: seeds not found: {path}", file=sys.stderr)
        return 1

    rows = load_seeds(path)
    en_rows = [r for r in rows if r.get("lang", "en") == "en"]
    by_label: dict[str, list[str]] = {}
    for r in en_rows:
        by_label.setdefault(r["label"], []).append(r["text"])

    langs = [l.strip() for l in args.langs.split(",") if l.strip()]
    print(f"head={args.head} en_seeds={len(en_rows)} "
          f"labels={{{', '.join(f'{k}:{len(v)}' for k, v in by_label.items())}}} "
          f"-> langs={langs}")

    if args.dry_run:
        for lang in langs:
            for label, texts in by_label.items():
                print(f"  would trans-create {len(texts)} {label} seeds -> {lang}")
        return 0

    api_key = load_api_key()
    if not api_key:
        print("ERROR: no API key (set $ANTHROPIC_API_KEY or ~/claudeapi.txt)",
              file=sys.stderr)
        return 1
    try:
        import anthropic
    except ImportError:
        print("ERROR: pip install anthropic", file=sys.stderr)
        return 1
    client = anthropic.Anthropic(api_key=api_key)

    # Start fresh: EN rows tagged, then appended target-language blocks.
    out_rows = [{"lang": "en", "label": r["label"], "text": r["text"]}
                for r in en_rows]
    for lang in langs:
        for label, texts in by_label.items():
            created = transcreate_block(client, texts, label, lang)
            for t in created:
                out_rows.append({"lang": lang, "label": label, "text": t})
            print(f"  {lang}/{label}: +{len(created)}")

    tmp = path.with_suffix(".jsonl.tmp")
    tmp.write_text(
        "".join(json.dumps(r, ensure_ascii=False) + "\n" for r in out_rows),
        encoding="utf-8")
    os.replace(tmp, path)
    print(f"WROTE {len(out_rows)} rows -> {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
