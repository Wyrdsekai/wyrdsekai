#!/usr/bin/env python3
"""
Phase 7 validation: prove the Forge consolidation loop actually improves the
classifier on novel patterns.

Procedure per head:
  1. Generate a challenge corpus via Claude API — paraphrases distinct from
     training seeds, including deliberately hard / unusual surface forms.
  2. Split challenge 50/50 into {pre_train, held_out}.
  3. Measure BASELINE accuracy on held_out using the shipped classifier.
  4. Simulate a Forge consolidation: merge pre_train as pseudo-labels with
     the shipped bootstrap corpus and retrain.
  5. Measure NEW accuracy on held_out using the retrained classifier.
  6. Report delta.

This is a *research* artifact, not a unit test. It proves the end-to-end claim:
interaction data → retrain → the resulting agent is measurably better on
novel patterns drawn from the same distribution.

Usage (requires ANTHROPIC_API_KEY in env or ~/claudeapi.txt):
    python3 scripts/classifier/validate_forge_loop.py \\
        --head request_type \\
        --challenge-size 160 \\
        --model claude-sonnet-4-6 \\
        --report-out /tmp/forge-validation-request_type.json

Creates temp training + probe files under WYRDSEKAI_VALIDATE_WORKDIR (default
/tmp/forge-validate-<head>/), leaves the shipped model untouched.
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

try:
    from anthropic import Anthropic
except ImportError:
    print("ERROR: install anthropic: pip install anthropic", file=sys.stderr)
    sys.exit(1)

try:
    import numpy as np
    import onnxruntime as ort
    from transformers import AutoTokenizer
except ImportError as e:
    print(f"ERROR: missing dep: {e}. Install scikit-learn, onnxruntime, "
          "skl2onnx, transformers.", file=sys.stderr)
    sys.exit(1)

REPO_ROOT = Path(__file__).resolve().parents[2]
EMBEDDING_ONNX = REPO_ROOT / "core/src/main/resources/models/minilm-l6-v2-q8.onnx"
PRETRAINED = REPO_ROOT / "core/src/main/resources/classifier/pretrained"
BOOTSTRAP = REPO_ROOT / "core/src/main/resources/classifier/bootstrap"
TOKENIZER_NAME = "sentence-transformers/all-MiniLM-L6-v2"
TRAIN_SCRIPT = REPO_ROOT / "scripts/classifier/train_classifier.py"


# ────────────────────────────────────────────────────────────────────
# Claude-side challenge generation
# ────────────────────────────────────────────────────────────────────

CHALLENGE_PROMPT = """You are generating a challenge corpus to evaluate a text classifier.

Category: **{label}**
Meaning: {semantics}

Generate {n} NEW examples for this category. Requirements:
- Each must clearly belong to category "{label}" and NOT another category.
- Make them DISTINCT from typical training examples — unusual surface forms,
  idioms, regional phrasings, elliptical constructions, colloquial or
  grammatically-imperfect variants, multi-sentence asides.
- Include some that are deliberately tricky (e.g., reflective-sounding but
  really factual; short chat that could be mistaken for something else).
- Vary length from 3 words to 3 sentences.
- Do NOT repeat phrasings. Do NOT prefix with labels or quotes.

Output: one example per line, raw text only.
"""


LABEL_SEMANTICS = {
    "chat": (
        "Conversational social turns: greetings, small talk, acknowledgments. "
        "Short, no specific task. Reply wants warmth, not information."
    ),
    "reflective": (
        "Requests for self-disclosure, introspection, meta-awareness from the "
        "companion. Asks her to look inward, not search."
    ),
    "emotional": (
        "User is in distress, grief, fear, loneliness. Needs empathy and "
        "presence, not information or tasks."
    ),
    "factual": (
        "Clear information lookup — facts, definitions, summaries, recipes. "
        "Short-to-medium scope, expected to complete quickly."
    ),
    "delegate": (
        "Research-shape requests explicitly scoped to take time. Keywords "
        "like 'while I wait', 'take your time', 'deep dive'. User wants "
        "background work with a report later."
    ),
    "action": (
        "Direct physical action: go somewhere, pick something up, examine an "
        "object, travel, manipulate. The verb is about movement or object "
        "manipulation, not information."
    ),
    "write": (
        "Requests to compose, save, or journal a piece of text. Notes, "
        "letters, reminders, journal entries. Output is a written artifact."
    ),
    "tell_someone": (
        "Requests to relay a message to a named third party."
    ),
    "clean": (
        "Direct first-person speech, ready to speak aloud. No meta-narration, "
        "process description, emote-as-thought, third-person self-reference, "
        "or dispatcher plumbing."
    ),
    "leaky": (
        "Draft that leaks reasoning or dispatcher plumbing to the listener. "
        "Meta-narration, process description, emote-as-thought, telemetry, "
        "plan-shape output."
    ),
}


def load_api_key() -> str:
    key = os.environ.get("ANTHROPIC_API_KEY")
    if key:
        return key
    key_file = os.environ.get("ANTHROPIC_API_KEY_FILE") or str(
        Path.home() / "claudeapi.txt")
    if Path(key_file).exists():
        return Path(key_file).read_text().strip()
    print("ERROR: no API key. Set ANTHROPIC_API_KEY, ANTHROPIC_API_KEY_FILE, "
          "or drop key at ~/claudeapi.txt", file=sys.stderr)
    sys.exit(2)


def generate_challenge(client: Anthropic, label: str, n: int,
                        model: str) -> list[str]:
    examples: list[str] = []
    seen: set[str] = set()
    per_call = 30
    call = 0
    while len(examples) < n and call < 20:
        call += 1
        want = min(per_call, n - len(examples))
        msg = client.messages.create(
            model=model,
            max_tokens=4096,
            messages=[{"role": "user", "content": CHALLENGE_PROMPT.format(
                label=label, semantics=LABEL_SEMANTICS.get(label, ""), n=want)}],
        )
        text = msg.content[0].text
        for raw in text.splitlines():
            raw = raw.strip().lstrip("-•*0123456789.) ").strip()
            if not raw or len(raw) < 3 or len(raw) > 500:
                continue
            key = raw.lower()
            if key in seen:
                continue
            seen.add(key)
            examples.append(raw)
            if len(examples) >= n:
                break
        print(f"  [challenge:{label}] {len(examples)}/{n}", file=sys.stderr)
    return examples[:n]


# ────────────────────────────────────────────────────────────────────
# Classifier eval
# ────────────────────────────────────────────────────────────────────

_tokenizer = None
_embed_sess = None


def embed(texts: list[str]) -> np.ndarray:
    global _tokenizer, _embed_sess
    if _tokenizer is None:
        _tokenizer = AutoTokenizer.from_pretrained(TOKENIZER_NAME)
    if _embed_sess is None:
        _embed_sess = ort.InferenceSession(str(EMBEDDING_ONNX))
    out = np.zeros((len(texts), 384), dtype=np.float32)
    for i in range(0, len(texts), 32):
        batch = texts[i:i+32]
        enc = _tokenizer(batch, padding=True, truncation=True,
                         max_length=128, return_tensors="np")
        inputs = {
            "input_ids": enc["input_ids"].astype(np.int64),
            "attention_mask": enc["attention_mask"].astype(np.int64),
        }
        if "token_type_ids" in enc:
            inputs["token_type_ids"] = enc["token_type_ids"].astype(np.int64)
        token_embs = _embed_sess.run(None, inputs)[0]
        mask = enc["attention_mask"].astype(np.float32)[:, :, None]
        summed = (token_embs * mask).sum(axis=1)
        counts = mask.sum(axis=1).clip(min=1)
        pooled = summed / counts
        norms = np.linalg.norm(pooled, axis=1, keepdims=True).clip(min=1e-9)
        out[i:i+len(batch)] = pooled / norms
    return out


def classify_with_onnx(onnx_path: Path, labels_path: Path,
                        texts: list[str]) -> list[tuple[str, float]]:
    labels = json.loads(labels_path.read_text())["labels"]
    sess = ort.InferenceSession(str(onnx_path))
    X = embed(texts)
    results: list[tuple[str, float]] = []
    # Process one at a time to keep output parsing simple — skl2onnx emits
    # probabilities as a sequence of maps.
    for i in range(len(texts)):
        outputs = sess.run(None, {"embedding": X[i:i+1].astype(np.float32)})
        # First output is predicted label index (int64)
        # Second is probabilities (list of dict[int64, float])
        probs = outputs[1][0] if len(outputs) > 1 else None
        if isinstance(probs, dict):
            idx = max(probs, key=probs.get)
            results.append((labels[int(idx)], float(probs[idx])))
        else:
            # Fallback: just take the predicted label
            idx = int(outputs[0][0])
            results.append((labels[idx], 1.0))
    return results


def accuracy(preds: list[tuple[str, float]], truth: list[str]) -> float:
    hits = sum(1 for (lab, _), t in zip(preds, truth) if lab == t)
    return hits / max(1, len(truth))


# ────────────────────────────────────────────────────────────────────
# Main
# ────────────────────────────────────────────────────────────────────

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--head", required=True,
                    choices=["request_type", "cleanliness"])
    ap.add_argument("--challenge-size", type=int, default=160,
                    help="Total challenge examples (split 50/50 pre/held-out)")
    ap.add_argument("--model", default="claude-sonnet-4-6")
    ap.add_argument("--workdir", type=Path,
                    default=None, help="Defaults to /tmp/forge-validate-<head>")
    ap.add_argument("--report-out", type=Path, default=None)
    ap.add_argument("--skip-retrain", action="store_true",
                    help="Skip retrain step (useful for re-running eval only)")
    args = ap.parse_args()

    workdir = args.workdir or Path(tempfile.gettempdir()) / f"forge-validate-{args.head}"
    workdir.mkdir(parents=True, exist_ok=True)
    print(f"Workdir: {workdir}", file=sys.stderr)

    # Determine labels for this head
    if args.head == "request_type":
        labels = ["chat", "reflective", "emotional", "factual",
                  "delegate", "action", "write", "tell_someone"]
    else:
        labels = ["clean", "leaky"]
    per_label = max(4, args.challenge_size // len(labels))
    print(f"Generating {per_label}×{len(labels)} = {per_label*len(labels)} "
          f"challenge examples", file=sys.stderr)

    # 1. Generate challenge corpus (cached — skip if already on disk)
    challenge_path = workdir / "challenge.jsonl"
    if challenge_path.exists():
        print(f"Reusing challenge at {challenge_path}", file=sys.stderr)
        challenge = []
        for line in challenge_path.read_text().splitlines():
            if line.strip():
                challenge.append(json.loads(line))
    else:
        client = Anthropic(api_key=load_api_key())
        challenge: list[dict] = []
        for label in labels:
            exs = generate_challenge(client, label, per_label, args.model)
            for ex in exs:
                challenge.append({"label": label, "text": ex})
        with challenge_path.open("w") as f:
            for rec in challenge:
                f.write(json.dumps(rec) + "\n")
        print(f"Wrote {len(challenge)} challenge examples to {challenge_path}",
              file=sys.stderr)

    # 2. Split 50/50 per-label
    import random
    random.seed(42)
    pre_train: list[dict] = []
    held_out: list[dict] = []
    by_label: dict[str, list[dict]] = {}
    for rec in challenge:
        by_label.setdefault(rec["label"], []).append(rec)
    for lab, items in by_label.items():
        random.shuffle(items)
        split = len(items) // 2
        pre_train.extend(items[:split])
        held_out.extend(items[split:])
    print(f"Split: {len(pre_train)} pre-train, {len(held_out)} held-out",
          file=sys.stderr)

    # 3. Baseline accuracy on held-out
    baseline_onnx = PRETRAINED / f"{args.head}.onnx"
    baseline_labels = PRETRAINED / f"{args.head}.labels.json"
    print("Measuring baseline accuracy...", file=sys.stderr)
    preds_baseline = classify_with_onnx(baseline_onnx, baseline_labels,
                                         [r["text"] for r in held_out])
    acc_baseline = accuracy(preds_baseline, [r["label"] for r in held_out])
    print(f"BASELINE on held-out: {acc_baseline:.4f}", file=sys.stderr)

    # 4. Build merged corpus: shipped bootstrap + pre_train as pseudo-labels
    merged_corpus = workdir / "merged-corpus.jsonl"
    new_onnx = workdir / f"{args.head}.onnx"
    new_labels = workdir / f"{args.head}.labels.json"

    if not args.skip_retrain:
        print("Building merged corpus...", file=sys.stderr)
        bootstrap = BOOTSTRAP / args.head / "expanded.jsonl"
        seen_texts = set()
        with merged_corpus.open("w") as out:
            for src in (bootstrap,):
                if not src.exists():
                    continue
                for line in src.read_text().splitlines():
                    if not line.strip():
                        continue
                    rec = json.loads(line)
                    key = rec["text"].lower().strip()
                    if key in seen_texts:
                        continue
                    seen_texts.add(key)
                    out.write(json.dumps({
                        "label": rec["label"], "text": rec["text"],
                        "source": rec.get("source", "seed"),
                    }) + "\n")
            added_new = 0
            for rec in pre_train:
                key = rec["text"].lower().strip()
                if key in seen_texts:
                    continue
                seen_texts.add(key)
                out.write(json.dumps({
                    "label": rec["label"], "text": rec["text"],
                    "source": "challenge-pseudo",
                }) + "\n")
                added_new += 1
        print(f"Merged corpus: {len(seen_texts)} total, +{added_new} from "
              f"challenge pre-train", file=sys.stderr)

        # 5. Retrain via the real train script
        print("Retraining...", file=sys.stderr)
        result = subprocess.run(
            ["python3", str(TRAIN_SCRIPT),
             "--corpus", str(merged_corpus),
             "--output", str(new_onnx),
             "--labels-output", str(new_labels)],
            check=True,
        )

    # 6. New accuracy on held-out
    print("Measuring new-model accuracy...", file=sys.stderr)
    preds_new = classify_with_onnx(new_onnx, new_labels,
                                    [r["text"] for r in held_out])
    acc_new = accuracy(preds_new, [r["label"] for r in held_out])
    print(f"NEW on held-out: {acc_new:.4f}", file=sys.stderr)

    # 7. Report
    delta = acc_new - acc_baseline
    # Per-label breakdown
    per_label_baseline: dict[str, tuple[int, int]] = {}
    per_label_new: dict[str, tuple[int, int]] = {}
    for rec, (pb, _) in zip(held_out, preds_baseline):
        h, t = per_label_baseline.get(rec["label"], (0, 0))
        per_label_baseline[rec["label"]] = (h + (1 if pb == rec["label"] else 0), t + 1)
    for rec, (pn, _) in zip(held_out, preds_new):
        h, t = per_label_new.get(rec["label"], (0, 0))
        per_label_new[rec["label"]] = (h + (1 if pn == rec["label"] else 0), t + 1)

    report = {
        "head": args.head,
        "challenge_size": len(challenge),
        "pre_train_size": len(pre_train),
        "held_out_size": len(held_out),
        "baseline_accuracy": acc_baseline,
        "new_accuracy": acc_new,
        "delta": delta,
        "improved": delta > 0,
        "per_label_baseline": {
            k: {"hits": v[0], "total": v[1], "acc": v[0]/max(1, v[1])}
            for k, v in per_label_baseline.items()
        },
        "per_label_new": {
            k: {"hits": v[0], "total": v[1], "acc": v[0]/max(1, v[1])}
            for k, v in per_label_new.items()
        },
    }

    print("\n════════ Phase 7 Validation Report ════════", file=sys.stderr)
    print(f"head             : {report['head']}", file=sys.stderr)
    print(f"challenge size   : {report['challenge_size']}", file=sys.stderr)
    print(f"baseline accuracy: {acc_baseline:.4f}", file=sys.stderr)
    print(f"new accuracy     : {acc_new:.4f}", file=sys.stderr)
    print(f"delta            : {delta:+.4f}  "
          f"({'IMPROVED' if delta > 0 else 'regressed' if delta < -1e-6 else 'flat'})",
          file=sys.stderr)
    print("\nPer-label:", file=sys.stderr)
    for lab in sorted(per_label_new.keys()):
        b = per_label_baseline.get(lab, (0, 0))
        n = per_label_new[lab]
        b_acc = b[0]/max(1, b[1])
        n_acc = n[0]/max(1, n[1])
        print(f"  {lab:15s}  {b_acc:.3f} → {n_acc:.3f}  "
              f"({n_acc-b_acc:+.3f})  ({n[1]} held-out)", file=sys.stderr)

    if args.report_out:
        args.report_out.write_text(json.dumps(report, indent=2))
        print(f"\nReport written to {args.report_out}", file=sys.stderr)

    return 0 if delta >= -0.02 else 1


if __name__ == "__main__":
    sys.exit(main())
