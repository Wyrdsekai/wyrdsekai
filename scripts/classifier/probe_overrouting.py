#!/usr/bin/env python3
# recipe-callable: local-ok
"""
Over-routing probe for a classifier head OPEN-R5 closure.

Replaces the tautology placeholder the `regression-probe` recipe step used to
emit. Loads the freshly-trained ONNX classifier + its labels.json, embeds
each anchor in `probe-anchors/<head>.jsonl` via the same paraphrase-multilingual
embedding path the runtime uses, classifies it, and asserts the predicted
label matches ground truth. The recipe gate fails if more than `--max-misses`
anchors are misclassified (default 2 out of 30).

Local-only by invariant. Output is a single JSON line on stdout that the
runtime's GATE step reads — no extra prose, no markdown, no logs. stderr
carries human-debuggable detail.

Usage (recipe path):
    python3 scripts/classifier/probe_overrouting.py \\
        --head task_present \\
        --classifier /tmp/task_present.onnx \\
        --labels /tmp/task_present.labels.json

Output (stdout, on success):
    {"overrouting_probe_passes": true, "anchors_tested": 30, "misclassified": 1}

Exit codes: 0 = pass (under threshold) or fail (over threshold) — the recipe
GATE step inspects the JSON, not the exit. Non-zero exit only on script
errors (missing files, malformed ONNX, etc.).
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

try:
    import numpy as np
    import onnxruntime as ort
    from transformers import AutoTokenizer
except ImportError as e:
    print(f"ERROR: missing dependency: {e}", file=sys.stderr)
    print("Install: pip install numpy onnxruntime transformers", file=sys.stderr)
    sys.exit(2)


REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_ANCHORS_DIR = REPO_ROOT / "core" / "src" / "main" / "resources" \
    / "classifier" / "probe-anchors"
# #1089 — .pkg installs don't ship the source-tree embedding ONNX. Look
# in $WYRDSEKAI_DATA_DIR/models/ (where wyrd setup downloads it) before
# falling back to the source-tree path.
# Decoupled 2026-05-29: this probe measures the CLASSIFIER, so it must embed with
# the SetFit-tuned classifier encoder (EmbeddingModel.PARAPHRASE_L12_SETFIT), not
# the stock retrieval q8 — otherwise the over-routing gate measures the wrong space.
_EMBEDDING_FILENAME = "paraphrase-multilingual-MiniLM-L12-v2-setfit-q8.onnx"
def _resolve_default_embedding() -> Path:
    candidates = []
    data_dir = os.environ.get("WYRDSEKAI_DATA_DIR")
    if data_dir:
        candidates.append(Path(data_dir) / "models" / _EMBEDDING_FILENAME)
    candidates.append(Path.home() / ".wyrdsekai" / "models" / _EMBEDDING_FILENAME)
    candidates.append(REPO_ROOT / "core" / "src" / "main" / "resources" / "models" / _EMBEDDING_FILENAME)
    for c in candidates:
        if c.is_file():
            return c
    return candidates[-1]
DEFAULT_EMBEDDING_ONNX = _resolve_default_embedding()
DEFAULT_TOKENIZER_NAME = "Xenova/paraphrase-multilingual-MiniLM-L12-v2"


def load_anchors(path: Path) -> tuple[list[str], list[str], list[str]]:
    """Returns (texts, labels, langs). `lang` defaults to "und" if the row
    has no `lang` field — older single-language anchor files keep working."""
    texts, labels, langs = [], [], []
    with path.open() as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            rec = json.loads(line)
            texts.append(rec["text"])
            labels.append(rec["label"])
            langs.append(rec.get("lang", "und"))
    return texts, labels, langs


def embed(texts: list[str], embedding_onnx: Path, tokenizer_name: str) -> np.ndarray:
    """Match train_classifier.py exactly: mean-pool + L2-normalize."""
    sess = ort.InferenceSession(str(embedding_onnx))
    tokenizer = AutoTokenizer.from_pretrained(tokenizer_name)
    out = np.zeros((len(texts), 384), dtype=np.float32)
    enc = tokenizer(texts, padding=True, truncation=True,
                    max_length=128, return_tensors="np")
    inputs = {
        "input_ids": enc["input_ids"].astype(np.int64),
        "attention_mask": enc["attention_mask"].astype(np.int64),
    }
    if "token_type_ids" in enc:
        inputs["token_type_ids"] = enc["token_type_ids"].astype(np.int64)
    tok_embs = sess.run(None, inputs)[0]
    mask = enc["attention_mask"].astype(np.float32)[:, :, None]
    summed = (tok_embs * mask).sum(axis=1)
    counts = mask.sum(axis=1).clip(min=1)
    pooled = summed / counts
    norms = np.linalg.norm(pooled, axis=1, keepdims=True).clip(min=1e-9)
    out[:] = pooled / norms
    return out


def classify(features: np.ndarray, classifier_onnx: Path,
             label_index: list[str]) -> list[str]:
    sess = ort.InferenceSession(str(classifier_onnx))
    inp_name = sess.get_inputs()[0].name
    # skl2onnx-exported LR yields two outputs: label + probabilities.
    # The label output is what we want — but skl2onnx sometimes encodes it
    # as int64 indices, sometimes as string labels. Handle both.
    results = sess.run(None, {inp_name: features.astype(np.float32)})
    pred = results[0]
    out = []
    for v in pred.ravel():
        if isinstance(v, (np.integer, int)):
            out.append(label_index[int(v)])
        else:
            out.append(str(v))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--head", required=True,
                    help="Classifier head name (e.g. task_present). "
                         "Resolves the anchor JSONL under probe-anchors/.")
    ap.add_argument("--classifier", required=True, type=Path,
                    help="Trained classifier ONNX (typically /tmp/<head>.onnx).")
    ap.add_argument("--labels", required=True, type=Path,
                    help="Labels JSON written by train_classifier.py.")
    ap.add_argument("--anchors", type=Path, default=None,
                    help="Override anchor JSONL path (default: by --head).")
    ap.add_argument("--embedding", type=Path, default=DEFAULT_EMBEDDING_ONNX)
    ap.add_argument("--tokenizer", type=str, default=DEFAULT_TOKENIZER_NAME)
    ap.add_argument("--max-misses", type=int, default=6,
                    help="Maximum misclassifications before the probe fails "
                         "(default 6 out of ~90 anchors — ~7%%, comparable "
                         "to the prior 2/30 threshold). Tuneable per head "
                         "if the anchor set grows.")
    ap.add_argument("--max-misses-per-lang", type=int, default=None,
                    help="Optional per-language fail threshold. When set, "
                         "each language's miss count must be ≤ this value "
                         "OR the overall --max-misses gate; whichever is "
                         "stricter governs. Catches silent regression in "
                         "one language even when totals stay under budget.")
    ap.add_argument("--max-misses-per-lang-map", type=str, default=None,
                    help="Per-LANGUAGE fail thresholds, e.g. 'en:12,es:12,ja:8'. "
                         "When set, each language's miss count must be ≤ its OWN "
                         "threshold — so a language that was strong (low baseline) "
                         "cannot silently regress up to the worst language's budget. "
                         "This is the baseline-relative non-regression gate the "
                         "release bake uses; overrides --max-misses-per-lang for "
                         "any language present in the map. Languages absent from "
                         "the map fall back to --max-misses-per-lang (if set).")
    args = ap.parse_args()

    per_lang_map = None
    if args.max_misses_per_lang_map:
        per_lang_map = {}
        for pair in args.max_misses_per_lang_map.split(","):
            pair = pair.strip()
            if not pair:
                continue
            lang, _, val = pair.partition(":")
            per_lang_map[lang.strip()] = int(val)

    anchors_path = args.anchors \
        or (DEFAULT_ANCHORS_DIR / f"{args.head}.jsonl")
    if not anchors_path.exists():
        print(f"ERROR: anchor JSONL not found: {anchors_path}", file=sys.stderr)
        print(json.dumps({"overrouting_probe_passes": False,
                          "error": "anchors_missing",
                          "anchors_path": str(anchors_path)}))
        sys.exit(0)  # Recipe gate handles the failure, not the exit code.

    if not args.classifier.exists():
        print(f"ERROR: classifier ONNX not found: {args.classifier}",
              file=sys.stderr)
        print(json.dumps({"overrouting_probe_passes": False,
                          "error": "classifier_missing"}))
        sys.exit(0)

    if not args.labels.exists():
        print(f"ERROR: labels JSON not found: {args.labels}", file=sys.stderr)
        print(json.dumps({"overrouting_probe_passes": False,
                          "error": "labels_missing"}))
        sys.exit(0)

    # Load labels.json — train_classifier.py writes either
    # {"labels": ["a","b"]} or {"label_to_idx": {...}} depending on version.
    with args.labels.open() as f:
        lbl_doc = json.load(f)
    if "labels" in lbl_doc and isinstance(lbl_doc["labels"], list):
        label_index = lbl_doc["labels"]
    elif "label_to_idx" in lbl_doc:
        pairs = sorted(lbl_doc["label_to_idx"].items(), key=lambda kv: kv[1])
        label_index = [k for k, _ in pairs]
    else:
        # Fallback: assume the doc IS the list.
        label_index = list(lbl_doc) if isinstance(lbl_doc, list) else []
    if not label_index:
        print("ERROR: empty label index", file=sys.stderr)
        print(json.dumps({"overrouting_probe_passes": False,
                          "error": "empty_label_index"}))
        sys.exit(0)

    texts, expected, langs = load_anchors(anchors_path)
    if not texts:
        print(f"ERROR: anchors file empty: {anchors_path}", file=sys.stderr)
        print(json.dumps({"overrouting_probe_passes": False,
                          "error": "anchors_empty"}))
        sys.exit(0)

    print(f"probe_overrouting: head={args.head} anchors={len(texts)} "
          f"langs={sorted(set(langs))} max_misses={args.max_misses}"
          + (f" max_misses_per_lang={args.max_misses_per_lang}"
             if args.max_misses_per_lang is not None else ""),
          file=sys.stderr)

    features = embed(texts, args.embedding, args.tokenizer)
    predicted = classify(features, args.classifier, label_index)

    misses = []
    per_lang_total: dict[str, int] = {}
    per_lang_miss: dict[str, int] = {}
    for i, (text, exp, got, lang) in enumerate(
            zip(texts, expected, predicted, langs)):
        per_lang_total[lang] = per_lang_total.get(lang, 0) + 1
        if exp != got:
            per_lang_miss[lang] = per_lang_miss.get(lang, 0) + 1
            misses.append({"text": text[:80], "expected": exp, "got": got,
                           "lang": lang})
            print(f"  MISS[{i}][{lang}]: expected={exp} got={got} :: "
                  f"{text[:60]}", file=sys.stderr)

    # Build per-lang summary in stable lang order.
    per_lang = {
        lang: {"total": per_lang_total[lang],
               "misclassified": per_lang_miss.get(lang, 0)}
        for lang in sorted(per_lang_total.keys())
    }
    overall_pass = len(misses) <= args.max_misses
    per_lang_pass = True
    worst_lang = None
    per_lang_regressions = {}
    if per_lang_map is not None or args.max_misses_per_lang is not None:
        for lang, stats in per_lang.items():
            # Per-language non-regression: prefer the language's OWN threshold
            # from the map; else fall back to the single per-lang budget.
            thr = None
            if per_lang_map is not None and lang in per_lang_map:
                thr = per_lang_map[lang]
            elif args.max_misses_per_lang is not None:
                thr = args.max_misses_per_lang
            if thr is not None and stats["misclassified"] > thr:
                per_lang_pass = False
                per_lang_regressions[lang] = {
                    "misclassified": stats["misclassified"], "threshold": thr}
                if worst_lang is None or stats["misclassified"] \
                        - thr > per_lang[worst_lang]["misclassified"] \
                        - per_lang_regressions[worst_lang]["threshold"]:
                    worst_lang = lang
    passed = overall_pass and per_lang_pass

    result = {
        "overrouting_probe_passes": passed,
        "anchors_tested": len(texts),
        "misclassified": len(misses),
        "max_misses": args.max_misses,
        "per_lang": per_lang,
    }
    if args.max_misses_per_lang is not None:
        result["max_misses_per_lang"] = args.max_misses_per_lang
    if per_lang_map is not None:
        result["max_misses_per_lang_map"] = per_lang_map
    if not per_lang_pass:
        result["per_lang_fail_lang"] = worst_lang
        result["per_lang_regressions"] = per_lang_regressions
    if misses and not passed:
        result["misses"] = misses[:5]  # First 5 only — keep stdout tight.
    print(json.dumps(result))
    sys.exit(0)


if __name__ == "__main__":
    main()
