#!/usr/bin/env python3
# recipe-callable: local-ok
"""Multi-task SetFit-style training for all enrolled classifier heads.

Replaces the frozen-embedding+LR/MLP approach with contrastive fine-tuning of
the shared paraphrase-multilingual-MiniLM-L12-v2 sentence transformer, plus
per-head MLP classifiers on the reshaped embeddings.

Algorithm (per arxiv.org/abs/2209.11055, implemented directly on sentence-
transformers + sklearn — no `setfit` package dependency to avoid API drift):

  Phase 1: gather seeds from all enrolled heads. Per head, sample contrastive
           pairs from the labeled examples — positives are same-label,
           negatives are different-label WITHIN THE SAME HEAD.
  Phase 2: fine-tune ONE shared sentence transformer on the union of all
           heads' contrastive pairs (multi-task). One epoch on N-per-anchor
           pair sampling. CosineSimilarityLoss; positives → 1.0, negatives → 0.0.
  Phase 3: re-embed all seeds with the fine-tuned encoder. Train a per-head
           MLP classifier on the reshaped embeddings.
  Phase 4: validate that the fine-tuned encoder preserves general semantic
           similarity (catastrophic-forgetting check) before declaring done.

Output structure: <out>/
  encoder/                              # fine-tuned SentenceTransformer
  heads/<head>.onnx                     # per-head MLP classifier
  heads/<head>.labels.json
  heads/<head>.val-accuracy.json
  setfit-manifest.json                  # provenance: which heads, train timing,
                                        # forgetting-check delta, seed hashes

Used by:
  - Bake host (RecipeBakeMain) — multi-head bake, ship the encoder+heads.
  - Production Forge (sleep-pass) — same script, runs on household GPU when
    available; falls back to legacy frozen-embedding+MLP for CPU-only.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import random
import sys
import time
from pathlib import Path
from typing import Optional

import numpy as np

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_BASE = "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2"
DEFAULT_OUT = REPO_ROOT / "data" / "setfit"

# Forgetting-check pairs (held outside any classifier head's labelspace).
# Cosine similarity on these pairs is expected to stay >0.85 after SetFit —
# substantial drop signals the encoder was over-specialized.
FORGETTING_CHECK_PAIRS = [
    ("The cat sat on the mat.", "A feline rested on the rug."),
    ("He went to the store.", "He visited the shop."),
    ("It's raining outside.", "There's rain falling outside."),
    ("She finished her homework.", "She completed her assignment."),
    ("El gato está durmiendo.", "El gato duerme."),
    ("Hace mucho calor hoy.", "Hoy hace mucho calor."),
    ("猫が眠っている。", "猫が寝ている。"),
    ("今日は暑い。", "今日は気温が高い。"),
]
FORGETTING_THRESHOLD = -0.15  # delta-cosine below this fails the gate


def load_jsonl(path: Path) -> list[dict]:
    rows = []
    with path.open() as f:
        for line in f:
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    return rows


def sha256_short(s: str) -> str:
    return hashlib.sha256(s.encode("utf-8")).hexdigest()[:12]


def make_contrastive_pairs(seeds_by_label: dict[str, list[str]],
                           n_per_anchor: int, rng: random.Random):
    """Generate (text_a, text_b, label) triples for one head.
    Positives have label=1.0, negatives 0.0. Same number of pos/neg per anchor."""
    from sentence_transformers import InputExample
    examples: list = []
    labels = list(seeds_by_label.keys())
    for label, members in seeds_by_label.items():
        # positives — same label, not self
        for anchor in members:
            pool = [m for m in members if m != anchor]
            for partner in rng.sample(pool, min(n_per_anchor, len(pool))):
                examples.append(InputExample(texts=[anchor, partner], label=1.0))
        # negatives — any other label within same head
        other_members: list[str] = []
        for other_label in labels:
            if other_label != label:
                other_members.extend(seeds_by_label[other_label])
        for anchor in members:
            for partner in rng.sample(other_members, min(n_per_anchor, len(other_members))):
                examples.append(InputExample(texts=[anchor, partner], label=0.0))
    return examples


def cosine(a: np.ndarray, b: np.ndarray) -> float:
    return float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b) + 1e-9))


def forgetting_delta(orig_encoder, tuned_encoder) -> dict:
    """Returns mean delta in cosine similarity on FORGETTING_CHECK_PAIRS.
    If finetune was surgical, mean delta should be near 0 (and definitely
    not below FORGETTING_THRESHOLD)."""
    a_texts = [p[0] for p in FORGETTING_CHECK_PAIRS]
    b_texts = [p[1] for p in FORGETTING_CHECK_PAIRS]
    orig_a = orig_encoder.encode(a_texts, normalize_embeddings=True, show_progress_bar=False)
    orig_b = orig_encoder.encode(b_texts, normalize_embeddings=True, show_progress_bar=False)
    tuned_a = tuned_encoder.encode(a_texts, normalize_embeddings=True, show_progress_bar=False)
    tuned_b = tuned_encoder.encode(b_texts, normalize_embeddings=True, show_progress_bar=False)
    orig_sims = [cosine(a, b) for a, b in zip(orig_a, orig_b)]
    tuned_sims = [cosine(a, b) for a, b in zip(tuned_a, tuned_b)]
    return {
        "n_pairs": len(FORGETTING_CHECK_PAIRS),
        "orig_mean_cosine": float(np.mean(orig_sims)),
        "tuned_mean_cosine": float(np.mean(tuned_sims)),
        "delta_mean_cosine": float(np.mean(tuned_sims) - np.mean(orig_sims)),
        "threshold": FORGETTING_THRESHOLD,
        "passes": float(np.mean(tuned_sims) - np.mean(orig_sims)) >= FORGETTING_THRESHOLD,
    }


def train_head_classifier(seed_embs: np.ndarray, y: np.ndarray,
                          n_labels: int, rng_seed: int):
    """Train an MLP head on the SetFit-reshaped embeddings."""
    from sklearn.neural_network import MLPClassifier
    # Hidden size scales gently with label count. 2-label: (256,128).
    # 8-label (request_type): (384, 192) so head can encode richer boundary.
    if n_labels <= 2:
        hidden = (256, 128)
    elif n_labels <= 4:
        hidden = (320, 160)
    else:
        hidden = (384, 192)
    clf = MLPClassifier(
        hidden_layer_sizes=hidden,
        max_iter=800,
        random_state=rng_seed,
        early_stopping=True,
        validation_fraction=0.15,
    ).fit(seed_embs, y)
    return clf


def export_mlp_to_onnx(clf, embedding_dim: int, out_path: Path):
    """Export an sklearn MLPClassifier to ONNX with skl2onnx."""
    from skl2onnx import convert_sklearn
    from skl2onnx.common.data_types import FloatTensorType
    initial_types = [("embedding_input", FloatTensorType([None, embedding_dim]))]
    onx = convert_sklearn(clf, initial_types=initial_types, target_opset=17)
    out_path.write_bytes(onx.SerializeToString())


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[1])
    ap.add_argument("--heads", default="task_present,request_type,cleanliness,substrate_present",
                    help="Comma-separated head names to train (multi-task).")
    ap.add_argument("--seeds-dir", type=Path,
                    default=REPO_ROOT / "core/src/main/resources/classifier/bootstrap",
                    help="Directory containing <head>/seeds.jsonl per head.")
    ap.add_argument("--output-dir", type=Path, default=DEFAULT_OUT,
                    help="Where to write encoder + heads + manifest.")
    ap.add_argument("--base-model", default=DEFAULT_BASE,
                    help="HuggingFace base sentence-transformer.")
    ap.add_argument("--n-per-anchor", type=int, default=10,
                    help="Contrastive pairs sampled per anchor per direction.")
    ap.add_argument("--epochs", type=int, default=1,
                    help="Contrastive fine-tune epochs.")
    ap.add_argument("--batch-size", type=int, default=16)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--device", default="auto", choices=["auto", "cuda", "cpu"])
    args = ap.parse_args()

    rng = random.Random(args.seed)
    np.random.seed(args.seed)

    # Lazy imports — installation footprint stays out of CPU-only households
    # until they actually invoke this script.
    try:
        from sentence_transformers import SentenceTransformer, losses
        from torch.utils.data import DataLoader
        import torch
    except ImportError as e:
        print(f"ERROR: SetFit training needs sentence-transformers + torch. {e}",
              file=sys.stderr)
        sys.exit(2)

    device = args.device
    if device == "auto":
        device = "cuda" if torch.cuda.is_available() else "cpu"
    print(f"[setfit] device={device} base={args.base_model}", file=sys.stderr)

    heads = [h.strip() for h in args.heads.split(",") if h.strip()]
    print(f"[setfit] heads={heads}", file=sys.stderr)

    # ── Phase 1: load seeds + build contrastive pairs ────────────────────
    all_pairs = []
    head_data: dict[str, dict] = {}
    for head in heads:
        seeds_path = args.seeds_dir / head / "seeds.jsonl"
        if not seeds_path.exists():
            print(f"ERROR: seeds not found for head '{head}': {seeds_path}",
                  file=sys.stderr)
            sys.exit(2)
        seeds = load_jsonl(seeds_path)
        by_label: dict[str, list[str]] = {}
        for r in seeds:
            by_label.setdefault(r["label"], []).append(r["text"])
        pairs = make_contrastive_pairs(by_label, args.n_per_anchor, rng)
        all_pairs.extend(pairs)
        head_data[head] = {
            "seeds_path": str(seeds_path),
            "seeds_count": len(seeds),
            "labels": sorted(by_label.keys()),
            "n_per_label": {l: len(v) for l, v in by_label.items()},
            "n_contrastive_pairs": len(pairs),
            "seed_hash": sha256_short("".join(r["text"] for r in seeds)),
        }
        print(f"[setfit]   {head}: {len(seeds)} seeds → {len(pairs)} pairs",
              file=sys.stderr)
    rng.shuffle(all_pairs)
    print(f"[setfit] total contrastive pairs: {len(all_pairs)}", file=sys.stderr)

    # ── Phase 2: train shared encoder ────────────────────────────────────
    orig_encoder = SentenceTransformer(args.base_model, device=device)
    tuned_encoder = SentenceTransformer(args.base_model, device=device)
    loader = DataLoader(all_pairs, shuffle=True, batch_size=args.batch_size)
    loss_fn = losses.CosineSimilarityLoss(tuned_encoder)

    t0 = time.time()
    tuned_encoder.fit(
        train_objectives=[(loader, loss_fn)],
        epochs=args.epochs,
        warmup_steps=int(len(loader) * 0.1),
        show_progress_bar=False,
    )
    train_seconds = time.time() - t0
    print(f"[setfit] encoder fine-tune: {train_seconds:.1f}s", file=sys.stderr)

    # ── Phase 4 (early): forgetting check before we keep going ───────────
    forgetting = forgetting_delta(orig_encoder, tuned_encoder)
    print(f"[setfit] forgetting check: orig={forgetting['orig_mean_cosine']:.3f} "
          f"tuned={forgetting['tuned_mean_cosine']:.3f} "
          f"delta={forgetting['delta_mean_cosine']:+.3f} "
          f"({'PASS' if forgetting['passes'] else 'FAIL'})",
          file=sys.stderr)
    if not forgetting["passes"]:
        # Welfare gate: catastrophic forgetting denies the result.
        print(f"ERROR: forgetting delta {forgetting['delta_mean_cosine']:+.3f} "
              f"below threshold {FORGETTING_THRESHOLD} — refusing to ship",
              file=sys.stderr)
        sys.exit(1)

    # ── Phase 3: train per-head MLP classifiers + export to ONNX ────────
    out = args.output_dir
    out.mkdir(parents=True, exist_ok=True)
    encoder_out = out / "encoder"
    encoder_out.mkdir(exist_ok=True)
    tuned_encoder.save(str(encoder_out))
    print(f"[setfit] saved encoder → {encoder_out}", file=sys.stderr)

    heads_out = out / "heads"
    heads_out.mkdir(exist_ok=True)

    for head in heads:
        seeds_path = args.seeds_dir / head / "seeds.jsonl"
        seeds = load_jsonl(seeds_path)
        texts = [r["text"] for r in seeds]
        labels_sorted = head_data[head]["labels"]
        label2id = {l: i for i, l in enumerate(labels_sorted)}
        y = np.array([label2id[r["label"]] for r in seeds], dtype=np.int64)
        embs = tuned_encoder.encode(texts, normalize_embeddings=True, show_progress_bar=False)
        clf = train_head_classifier(embs, y, n_labels=len(labels_sorted),
                                    rng_seed=args.seed)
        train_score = clf.score(embs, y)
        head_data[head]["train_accuracy"] = float(train_score)
        head_data[head]["embedding_dim"] = int(embs.shape[1])
        # ONNX export
        try:
            export_mlp_to_onnx(clf, embs.shape[1],
                               heads_out / f"{head}.onnx")
        except ImportError as e:
            print(f"WARN: skl2onnx not installed — skipping ONNX export for {head}: {e}",
                  file=sys.stderr)
        # Labels + accuracy sidecars (mirror train_classifier.py output shape)
        (heads_out / f"{head}.labels.json").write_text(json.dumps({
            "labels": labels_sorted,
            "label_to_idx": label2id,
        }, indent=2, ensure_ascii=False))
        (heads_out / f"{head}.val-accuracy.json").write_text(json.dumps({
            "accuracy": float(train_score),
            "training_examples": len(seeds),
            "validation_examples": 0,
            "note": "train-set accuracy from SetFit MLP head; held-out probe is via probe_overrouting.py",
            "labels": labels_sorted,
        }, indent=2))
        print(f"[setfit]   {head}: train acc {train_score:.4f}, dim {embs.shape[1]}",
              file=sys.stderr)

    # ── Manifest (provenance) ────────────────────────────────────────────
    manifest = {
        "schema": "wyrdsekai.setfit.v1",
        "base_model": args.base_model,
        "device": device,
        "epochs": args.epochs,
        "batch_size": args.batch_size,
        "n_per_anchor": args.n_per_anchor,
        "total_contrastive_pairs": len(all_pairs),
        "train_seconds": train_seconds,
        "heads": head_data,
        "forgetting_check": forgetting,
        "seed": args.seed,
    }
    (out / "setfit-manifest.json").write_text(json.dumps(manifest, indent=2))
    print(f"\n[setfit] DONE — manifest at {out / 'setfit-manifest.json'}",
          file=sys.stderr)

    # Echo summary JSON on stdout for the recipe gate to read.
    print(json.dumps({
        "setfit_succeeded": True,
        "encoder_dir": str(encoder_out),
        "heads_dir": str(heads_out),
        "train_seconds": train_seconds,
        "forgetting_delta": forgetting["delta_mean_cosine"],
        "head_accuracies": {h: head_data[h].get("train_accuracy") for h in heads},
    }))


if __name__ == "__main__":
    main()
