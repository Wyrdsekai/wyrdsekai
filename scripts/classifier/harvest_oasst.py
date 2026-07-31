#!/usr/bin/env python3
"""
Full oasst1 harvest with classifier-assisted labeling.

Pipeline:
  1. Load full OpenAssistant/oasst1 train+validation (~89k rows)
  2. Filter: first-turn prompter messages in English, length 10–500 chars
  3. Embed each via minilm-l6-v2 (same ONNX the Java runtime uses)
  4. Classify via the SHIPPED request_type.onnx — the real classifier, not
     keyword heuristics
  5. Keep predictions at confidence >= CONF_THRESHOLD (default 0.70)
  6. Dedupe by normalized text
  7. Write JSONL + distribution report

Output is NOT directly merged into the training corpus — it's a candidate
file for human spot-check before commit. Run `train_classifier.py` on the
merged corpus once you're satisfied with the label quality.

Usage:
    python3 scripts/classifier/harvest_oasst.py \\
        --out core/src/main/resources/classifier/harvest/oasst1-candidates.jsonl \\
        --min-confidence 0.70
"""
import argparse
import json
import sys
from pathlib import Path

try:
    import numpy as np
    import onnxruntime as ort
    from datasets import load_dataset
    from transformers import AutoTokenizer
except ImportError as e:
    print(f"ERROR: missing dep: {e}", file=sys.stderr)
    print("pip install datasets onnxruntime transformers numpy", file=sys.stderr)
    sys.exit(1)


REPO_ROOT = Path(__file__).resolve().parents[2]
EMBEDDING_ONNX = REPO_ROOT / "core/src/main/resources/models/minilm-l6-v2-q8.onnx"
CLASSIFIER_ONNX = REPO_ROOT / "core/src/main/resources/classifier/pretrained/request_type.onnx"
LABELS_JSON = REPO_ROOT / "core/src/main/resources/classifier/pretrained/request_type.labels.json"
TOKENIZER_NAME = "sentence-transformers/all-MiniLM-L6-v2"


def load_classifier():
    labels = json.loads(LABELS_JSON.read_text())["labels"]
    embed_sess = ort.InferenceSession(str(EMBEDDING_ONNX))
    clf_sess = ort.InferenceSession(str(CLASSIFIER_ONNX))
    tokenizer = AutoTokenizer.from_pretrained(TOKENIZER_NAME)
    return labels, embed_sess, clf_sess, tokenizer


def embed_batch(texts, tokenizer, embed_sess):
    enc = tokenizer(texts, padding=True, truncation=True,
                    max_length=128, return_tensors="np")
    inputs = {
        "input_ids": enc["input_ids"].astype(np.int64),
        "attention_mask": enc["attention_mask"].astype(np.int64),
    }
    if "token_type_ids" in enc:
        inputs["token_type_ids"] = enc["token_type_ids"].astype(np.int64)
    token_embs = embed_sess.run(None, inputs)[0]
    mask = enc["attention_mask"].astype(np.float32)[:, :, None]
    summed = (token_embs * mask).sum(axis=1)
    counts = mask.sum(axis=1).clip(min=1)
    pooled = summed / counts
    norms = np.linalg.norm(pooled, axis=1, keepdims=True).clip(min=1e-9)
    return pooled / norms


def classify(texts, labels, embed_sess, clf_sess, tokenizer):
    """Return list of (label, confidence) tuples."""
    X = embed_batch(texts, tokenizer, embed_sess).astype(np.float32)
    out = []
    # One-at-a-time to match the Java pipeline's batch=1 behavior (simpler
    # unpack of the skl2onnx probabilities output, which is a sequence of dicts).
    for i in range(len(texts)):
        outputs = clf_sess.run(None, {"embedding": X[i:i+1]})
        probs = outputs[1][0] if len(outputs) > 1 else None
        if isinstance(probs, dict):
            idx = max(probs, key=probs.get)
            out.append((labels[int(idx)], float(probs[idx])))
        else:
            idx = int(outputs[0][0])
            out.append((labels[idx], 1.0))
    return out


def normalize(text):
    return " ".join(text.lower().split())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--min-confidence", type=float, default=0.70)
    ap.add_argument("--limit", type=int, default=0,
                    help="Limit rows scanned (0 = all)")
    args = ap.parse_args()

    print("Loading OpenAssistant/oasst1...", file=sys.stderr)
    train = load_dataset("OpenAssistant/oasst1", split="train")
    val = load_dataset("OpenAssistant/oasst1", split="validation")

    print("Loading classifier + embedder...", file=sys.stderr)
    labels, embed_sess, clf_sess, tokenizer = load_classifier()

    # Collect candidate texts
    candidates = []
    for ds in (train, val):
        for row in ds:
            if row.get("role") != "prompter": continue
            if row.get("parent_id") is not None: continue
            if row.get("lang") != "en": continue
            text = (row.get("text") or "").strip()
            if len(text) < 10 or len(text) > 500: continue
            candidates.append(text)
            if args.limit and len(candidates) >= args.limit:
                break
        if args.limit and len(candidates) >= args.limit:
            break

    print(f"Found {len(candidates)} candidate prompts", file=sys.stderr)

    # Batch classify (batch size 64 for throughput)
    BATCH = 64
    labeled = []
    kept_keys = set()
    label_counts = {lbl: 0 for lbl in labels}
    low_conf = 0

    for i in range(0, len(candidates), BATCH):
        batch = candidates[i:i+BATCH]
        preds = classify(batch, labels, embed_sess, clf_sess, tokenizer)
        for text, (lbl, conf) in zip(batch, preds):
            if conf < args.min_confidence:
                low_conf += 1
                continue
            key = normalize(text)
            if key in kept_keys:
                continue
            kept_keys.add(key)
            labeled.append({
                "label": lbl,
                "text": text,
                "source": "oasst1-classified",
                "confidence": round(conf, 4),
            })
            label_counts[lbl] += 1
        if (i // BATCH) % 20 == 0:
            print(f"  progress: {i+len(batch)}/{len(candidates)} "
                  f"(kept {len(labeled)}, low-conf skipped {low_conf})",
                  file=sys.stderr)

    args.out.parent.mkdir(parents=True, exist_ok=True)
    with args.out.open("w") as f:
        for rec in labeled:
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")

    print(f"\nWrote {len(labeled)} labeled records to {args.out}", file=sys.stderr)
    print(f"Low-confidence skipped: {low_conf} (below {args.min_confidence})",
          file=sys.stderr)
    print("\nLabel distribution (classifier-assisted):", file=sys.stderr)
    total = len(labeled) or 1
    for lbl, count in sorted(label_counts.items(), key=lambda kv: -kv[1]):
        pct = 100 * count / total
        print(f"  {lbl:15s}  {count:5d}  ({pct:.1f}%)", file=sys.stderr)

    # Print a few samples per label so you can eyeball label quality.
    print("\nSample records by label (first 3 per label):", file=sys.stderr)
    seen = {}
    for rec in labeled:
        c = seen.get(rec["label"], 0)
        if c < 3:
            seen[rec["label"]] = c + 1
            preview = rec["text"][:140].replace("\n", " ")
            print(f"  [{rec['label']:13s}] ({rec['confidence']:.2f}) {preview}",
                  file=sys.stderr)


if __name__ == "__main__":
    main()
