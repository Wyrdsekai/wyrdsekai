#!/usr/bin/env python3
# recipe-callable: local-ok
"""
Train a text classifier on an expanded corpus and export to ONNX so the Java
runtime can load it via onnxruntime-java.

Local-only by invariant (recipe-callable: local-ok). Uses sklearn +
onnxruntime + transformers — all offline. No cloud API calls, no internet
required. Household-evolves with no key.

Pipeline:
    text → embedding (384-d) → logistic regression (multi-class)

Embedding defaults to the bundled paraphrase-multilingual-MiniLM-L12-v2 (the
runtime's current registry default — see EmbeddingModel.PARAPHRASE_L12). The
Java side mirrors this load exactly. Override with --embedding/--tokenizer
when retraining heads against a different model swap.

Usage:
    python train_classifier.py \\
        --corpus core/src/main/resources/classifier/bootstrap/request_type/expanded.jsonl \\
        --output core/src/main/resources/classifier/pretrained/request_type.onnx \\
        --labels-output core/src/main/resources/classifier/pretrained/request_type.labels.json

Requires: scikit-learn, onnxruntime, skl2onnx, transformers (for tokenizer).
"""
import argparse
import json
import os
import sys
from pathlib import Path

try:
    import numpy as np
    import onnxruntime as ort
    from sklearn.linear_model import LogisticRegression
    from sklearn.neural_network import MLPClassifier
    from sklearn.model_selection import train_test_split
    from sklearn.metrics import classification_report, confusion_matrix
    from skl2onnx import convert_sklearn
    from skl2onnx.common.data_types import FloatTensorType
    from transformers import AutoTokenizer
except ImportError as e:
    print(f"ERROR: missing dependency: {e}", file=sys.stderr)
    print("Install: pip install scikit-learn onnxruntime skl2onnx transformers",
          file=sys.stderr)
    sys.exit(1)


# Default embedding ONNX — the CLASSIFIER feature encoder, i.e.
# EmbeddingModel.PARAPHRASE_L12_SETFIT (the SetFit-tuned encoder the heads are
# trained against), NOT the stock retrieval default PARAPHRASE_L12. Decoupled
# 2026-05-29: the
# heads consume THIS encoder's feature space, so head training must use it.
# Training against the stock retrieval q8 would silently undo the substrate_present
# fix. (The retrain recipe passes the freshly-tuned encoder explicitly via
# --embedding; this default only governs standalone/manual runs.)
#
# Resolution order (#1089 — .pkg installs don't have the source tree):
#   1. $WYRDSEKAI_DATA_DIR/models/paraphrase-multilingual-MiniLM-L12-v2-setfit-q8.onnx
#   2. ~/.wyrdsekai/models/paraphrase-multilingual-MiniLM-L12-v2-setfit-q8.onnx
#   3. REPO_ROOT/core/src/main/resources/models/...  (source-mode dev path)
# First existing file wins. None of them being present is the legitimate
# "you need to run `wyrd setup` first" error case — the file existence check
# at __main__ time prints the actionable hint.
REPO_ROOT = Path(__file__).resolve().parents[2]
_EMBEDDING_FILENAME = "paraphrase-multilingual-MiniLM-L12-v2-setfit-q8.onnx"
def _resolve_default_embedding() -> Path:
    candidates = []
    data_dir = os.environ.get("WYRDSEKAI_DATA_DIR")
    if data_dir:
        candidates.append(Path(data_dir) / "models" / _EMBEDDING_FILENAME)
    candidates.append(Path.home() / ".wyrdsekai" / "models" / _EMBEDDING_FILENAME)
    candidates.append(REPO_ROOT / "core/src/main/resources/models" / _EMBEDDING_FILENAME)
    for c in candidates:
        if c.is_file():
            return c
    # Return the source-tree path as the default-when-none-exists so the
    # existence check downstream gives a stable error message.
    return candidates[-1]
DEFAULT_EMBEDDING_ONNX = _resolve_default_embedding()
# Tokenizer — HF Xenova mirror of paraphrase-multilingual-MiniLM-L12-v2 (the
# repo that hosts the int8 ONNX export, kept in lockstep with EmbeddingModel).
DEFAULT_TOKENIZER_NAME = "Xenova/paraphrase-multilingual-MiniLM-L12-v2"


def load_corpus(path: Path) -> tuple[list[str], list[str]]:
    texts, labels = [], []
    with path.open() as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            rec = json.loads(line)
            texts.append(rec["text"])
            labels.append(rec["label"])
    return texts, labels


def embed_texts(texts: list[str], embedding_onnx: Path, tokenizer_name: str) -> np.ndarray:
    """Embed via the configured ONNX. Mean-pool over tokens, L2-normalize."""
    print(f"Loading embedding model from {embedding_onnx}", file=sys.stderr)
    print(f"Loading tokenizer {tokenizer_name}", file=sys.stderr)
    sess = ort.InferenceSession(str(embedding_onnx))
    tokenizer = AutoTokenizer.from_pretrained(tokenizer_name)

    embeddings = np.zeros((len(texts), 384), dtype=np.float32)
    batch_size = 32
    for i in range(0, len(texts), batch_size):
        batch = texts[i:i + batch_size]
        enc = tokenizer(batch, padding=True, truncation=True,
                        max_length=128, return_tensors="np")
        inputs = {
            "input_ids": enc["input_ids"].astype(np.int64),
            "attention_mask": enc["attention_mask"].astype(np.int64),
        }
        if "token_type_ids" in enc:
            inputs["token_type_ids"] = enc["token_type_ids"].astype(np.int64)
        out = sess.run(None, inputs)
        # out[0] is (batch, seq_len, 384) token embeddings. Mean-pool over
        # non-pad tokens, then L2-normalize — matches sentence-transformers.
        token_embs = out[0]
        mask = enc["attention_mask"].astype(np.float32)[:, :, None]
        summed = (token_embs * mask).sum(axis=1)
        counts = mask.sum(axis=1).clip(min=1)
        pooled = summed / counts
        norms = np.linalg.norm(pooled, axis=1, keepdims=True).clip(min=1e-9)
        embeddings[i:i + len(batch)] = pooled / norms
        if i % 256 == 0:
            print(f"  embedded {i + len(batch)}/{len(texts)}", file=sys.stderr)
    return embeddings


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--corpus", required=True, type=Path)
    ap.add_argument("--output", required=True, type=Path,
                    help="Path to write classifier .onnx")
    ap.add_argument("--labels-output", required=True, type=Path,
                    help="Path to write label index JSON")
    ap.add_argument("--test-size", type=float, default=0.2)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--embedding", type=Path, default=DEFAULT_EMBEDDING_ONNX,
                    help="Path to embedding ONNX (default: paraphrase-multilingual-MiniLM-L12-v2)")
    ap.add_argument("--tokenizer", type=str, default=DEFAULT_TOKENIZER_NAME,
                    help="HF tokenizer id (default: Xenova/paraphrase-multilingual-MiniLM-L12-v2)")
    ap.add_argument("--embedding-label", type=str, default=None,
                    help="Identifier written into labels.json (default: derived from --embedding)")
    ap.add_argument("--classifier", choices=["lr", "mlp"], default="lr",
                    help="Classifier head: lr=LogisticRegression (default), mlp=MLPClassifier(128)")
    args = ap.parse_args()

    if not args.embedding.exists():
        print(f"ERROR: embedding ONNX not found: {args.embedding}", file=sys.stderr)
        print("Run `wyrd setup` or `wyrd embedding-model download paraphrase-l12`",
              file=sys.stderr)
        sys.exit(1)

    embedding_label = args.embedding_label or args.embedding.stem.replace("-q8", "")

    texts, labels = load_corpus(args.corpus)
    print(f"Loaded {len(texts)} examples across {len(set(labels))} labels",
          file=sys.stderr)

    # Embed
    X = embed_texts(texts, args.embedding, args.tokenizer)
    # Label encode — keep a stable ordering for ONNX output
    unique_labels = sorted(set(labels))
    label_to_idx = {lbl: i for i, lbl in enumerate(unique_labels)}
    y = np.array([label_to_idx[lbl] for lbl in labels], dtype=np.int64)

    # Split
    X_tr, X_te, y_tr, y_te = train_test_split(
        X, y, test_size=args.test_size, random_state=args.seed, stratify=y)

    # Train
    if args.classifier == "mlp":
        # One hidden layer, 128 units. Captures non-linear separation that
        # multilingual embeddings need on small corpora — LR sometimes leaves
        # 5-10pp on the table for this task.
        clf = MLPClassifier(hidden_layer_sizes=(128,), max_iter=500,
                            early_stopping=True, validation_fraction=0.1,
                            random_state=args.seed)
        classifier_label = "MLPClassifier(128)"
    else:
        # sklearn ≥1.7 defaults LR to multinomial — multi_class arg removed.
        clf = LogisticRegression(max_iter=2000, C=1.0, class_weight="balanced",
                                 solver="lbfgs")
        classifier_label = "LogisticRegression"
    clf.fit(X_tr, y_tr)

    # Evaluate
    y_pred = clf.predict(X_te)
    accuracy = float((y_pred == y_te).mean())
    print("\n=== Validation Report ===", file=sys.stderr)
    print(classification_report(
        y_te, y_pred, target_names=unique_labels, digits=3), file=sys.stderr)
    print("Confusion matrix (rows=true, cols=pred):", file=sys.stderr)
    print(f"Labels (in order): {unique_labels}", file=sys.stderr)
    print(confusion_matrix(y_te, y_pred), file=sys.stderr)

    # Export to ONNX
    args.output.parent.mkdir(parents=True, exist_ok=True)
    initial_types = [("embedding", FloatTensorType([None, 384]))]
    onnx_model = convert_sklearn(clf, initial_types=initial_types,
                                 target_opset=17)
    with args.output.open("wb") as f:
        f.write(onnx_model.SerializeToString())

    # Write labels
    with args.labels_output.open("w") as f:
        json.dump({
            "labels": unique_labels,
            "feature_dim": 384,
            "embedding_model": embedding_label,
            "classifier": classifier_label,
            "training_examples": len(X_tr),
            "validation_examples": len(X_te),
        }, f, indent=2)

    # Write val-accuracy sidecar for the Forge regression guard. Path is
    # <output-dir>/<output-basename>.val-accuracy.json — co-located with the
    # ONNX so a future Forge pass can read it without separate CLI plumbing.
    acc_path = args.output.with_name(args.output.stem + ".val-accuracy.json")
    with acc_path.open("w") as f:
        json.dump({
            "accuracy": accuracy,
            "training_examples": len(X_tr),
            "validation_examples": len(X_te),
            "labels": unique_labels,
        }, f, indent=2)

    print(f"\nWrote classifier to {args.output}", file=sys.stderr)
    print(f"Wrote labels to {args.labels_output}", file=sys.stderr)
    print(f"Wrote val accuracy {accuracy:.4f} to {acc_path}", file=sys.stderr)


if __name__ == "__main__":
    main()
