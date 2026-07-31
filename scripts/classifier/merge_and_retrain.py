#!/usr/bin/env python3
"""
Merge harvested corpora (oasst + persona) with the shipped bootstrap,
dedupe, and retrain the REQUEST_TYPE classifier. Reports val accuracy
delta vs baseline.

Sources:
  1. Shipped bootstrap (expanded.jsonl) — Sonnet paraphrases of seeds
  2. persona-candidates.jsonl — Opus persona-swap (full distribution)
  3. oasst1-candidates.jsonl — filtered to labels where chatbot-distribution
     overlaps with Wyrd-distribution

Merge policy:
  - oasst: ALLOWLIST to {factual, write, chat, reflective}. Action /
    tell_someone / delegate / emotional predictions on oasst data are
    unreliable (the classifier is forced to pick from 8 labels on a
    distribution without those shapes; predictions become noise).
  - persona: keep everything — Opus-generated with explicit label targets.
  - bootstrap: keep everything — known-good.
  - Dedupe by normalized text across all sources.

Output: merged-corpus.jsonl + retrain using train_classifier.py.

Usage:
    python3 scripts/classifier/merge_and_retrain.py
"""
import json
import os
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
BOOTSTRAP = REPO_ROOT / "core/src/main/resources/classifier/bootstrap/request_type/expanded.jsonl"
PERSONA = REPO_ROOT / "core/src/main/resources/classifier/harvest/persona-candidates.jsonl"
OASST = REPO_ROOT / "core/src/main/resources/classifier/harvest/oasst1-candidates.jsonl"

OUT_CORPUS = REPO_ROOT / "core/src/main/resources/classifier/harvest/request_type-merged.jsonl"
OUT_MODEL = REPO_ROOT / "core/src/main/resources/classifier/pretrained/request_type.onnx"
OUT_LABELS = REPO_ROOT / "core/src/main/resources/classifier/pretrained/request_type.labels.json"

OASST_ALLOWLIST = {"factual", "write", "chat", "reflective"}


def normalize(t):
    return " ".join(t.lower().split())


def load_jsonl(path):
    if not path.exists(): return []
    return [json.loads(l) for l in path.read_text().splitlines() if l.strip()]


def main():
    bootstrap = load_jsonl(BOOTSTRAP)
    persona = load_jsonl(PERSONA)
    oasst_all = load_jsonl(OASST)
    oasst_filtered = [r for r in oasst_all if r["label"] in OASST_ALLOWLIST]

    print(f"Bootstrap (Sonnet seeds+expansion): {len(bootstrap)}", file=sys.stderr)
    print(f"Persona (Opus): {len(persona)}", file=sys.stderr)
    print(f"oasst1 filtered (factual/write/chat/reflective only): "
          f"{len(oasst_filtered)} / {len(oasst_all)} total", file=sys.stderr)

    # Merge + dedupe by normalized text
    seen = set()
    merged = []
    sources_count = {"bootstrap": 0, "persona": 0, "oasst": 0}

    # Order: bootstrap first (known-good), then persona (voice variance),
    # then oasst (real distribution). Dedupe keeps earliest — bootstrap wins
    # ties.
    for src_name, records in [
            ("bootstrap", bootstrap),
            ("persona", persona),
            ("oasst", oasst_filtered)]:
        for r in records:
            text = r.get("text", "").strip()
            label = r.get("label", "").strip()
            if not text or not label: continue
            key = normalize(text)
            if key in seen: continue
            seen.add(key)
            merged.append({
                "label": label, "text": text,
                "source": r.get("source", src_name),
            })
            sources_count[src_name] += 1

    OUT_CORPUS.parent.mkdir(parents=True, exist_ok=True)
    with OUT_CORPUS.open("w") as f:
        for rec in merged:
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")

    print(f"\nMerged corpus: {len(merged)} records (dedupe'd)", file=sys.stderr)
    print(f"  from bootstrap: {sources_count['bootstrap']}", file=sys.stderr)
    print(f"  from persona:   {sources_count['persona']}", file=sys.stderr)
    print(f"  from oasst:     {sources_count['oasst']}", file=sys.stderr)
    print(f"Written to {OUT_CORPUS}", file=sys.stderr)

    # Label distribution
    lbl_counts = {}
    for r in merged:
        lbl_counts[r["label"]] = lbl_counts.get(r["label"], 0) + 1
    print("\nMerged label distribution:", file=sys.stderr)
    for l, c in sorted(lbl_counts.items(), key=lambda kv: -kv[1]):
        print(f"  {l:15s}  {c}", file=sys.stderr)

    # Retrain via train_classifier.py
    print("\n=== Retraining ===", file=sys.stderr)
    train_script = REPO_ROOT / "scripts/classifier/train_classifier.py"
    result = subprocess.run([
        sys.executable, str(train_script),
        "--corpus", str(OUT_CORPUS),
        "--output", str(OUT_MODEL),
        "--labels-output", str(OUT_LABELS),
    ], capture_output=True, text=True)
    print(result.stderr, file=sys.stderr)
    if result.returncode != 0:
        print(f"\nRetrain failed ({result.returncode})", file=sys.stderr)
        sys.exit(1)

    print(f"\nDone. New model at {OUT_MODEL}", file=sys.stderr)


if __name__ == "__main__":
    main()
