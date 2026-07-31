#!/usr/bin/env python3
# recipe-callable: local-ok
"""Export a SetFit-fine-tuned sentence transformer to ONNX (int8 quantized).

Reads the encoder produced by train_setfit.py (saved as a sentence-transformers
directory), exports the underlying transformer + mean-pooling to ONNX via
HuggingFace optimum, then quantizes to int8 to match the bundled artifact
format the runtime expects.

Produces an ONNX structurally identical to the stock retrieval encoder (same
XLM-R tokenizer + 384-d output), but it deploys to the CLASSIFIER slot
  models/paraphrase-multilingual-MiniLM-L12-v2-setfit-q8.onnx
(EmbeddingModel.PARAPHRASE_L12_SETFIT), NOT the stock retrieval encoder. The two
are decoupled (2026-05-29): SetFit tuning sharpens classifier separation but
degrades general retrieval, so the retrieval encoder stays stock. Do NOT write
this output over models/…-q8.onnx — that re-creates the retrieval regression.
.

Also runs a parity check: embeds a few sample sentences with both the PyTorch
encoder and the exported ONNX, asserts cosine similarity is ≥0.99. Catches
quantization-induced drift before ship.

Usage:
    python3 scripts/classifier/export_setfit_encoder_onnx.py \\
        --encoder data/setfit/encoder \\
        --output  data/setfit/encoder-q8.onnx \\
        --quantize int8
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np


PARITY_SAMPLES = [
    "translate this email into spanish",
    "I'm exhausted, just give me the answer",
    "今日は疲れた。",
    "Búscame la fuente de este artículo.",
    "The cat sat on the mat.",
]
PARITY_THRESHOLD = 0.99  # min cosine between PyTorch and ONNX outputs


def cosine(a: np.ndarray, b: np.ndarray) -> float:
    return float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b) + 1e-9))


def export_fp32(encoder_dir: Path, out_path: Path):
    """Export a SetFit-tuned SentenceTransformer to FP32 ONNX via HuggingFace
    optimum. Output: {input_ids, attention_mask, [token_type_ids]} →
    last_hidden_state, matching the runtime's bundled embedder shape.

    We use `ORTModelForFeatureExtraction.from_pretrained(..., export=True)`
    instead of calling `torch.onnx.export` directly because torch 2.12.0's
    new onnxscript-based exporter produces fragmented ONNX graphs on Apple
    Silicon for XLM-R MiniLM (Compute MatMul dimension mismatch at runtime).
    Optimum routes through its own tested exporter that handles XLM-R
    transformer classes correctly on every platform we ship to. See
for full diagnosis.

    Mean-pooling + normalization stay in the calling code (the Java runtime
    handles them; see EmbeddingModel.embed in core).
    """
    from optimum.onnxruntime import ORTModelForFeatureExtraction

    out_path.parent.mkdir(parents=True, exist_ok=True)

    # Optimum's exporter writes model.onnx (+ config.json + tokenizer.json
    # etc.) into a directory. We pull just the .onnx file out.
    with tempfile.TemporaryDirectory(prefix="optimum-export-") as tmp:
        tmp_dir = Path(tmp)
        ort_model = ORTModelForFeatureExtraction.from_pretrained(
            str(encoder_dir),
            export=True,
        )
        ort_model.save_pretrained(str(tmp_dir))
        src_onnx = tmp_dir / "model.onnx"
        if not src_onnx.exists():
            raise RuntimeError(f"optimum did not produce model.onnx at {src_onnx}")
        out_path.write_bytes(src_onnx.read_bytes())

    print(f"[export] FP32 → {out_path} ({out_path.stat().st_size // (1024*1024)} MB)",
          file=sys.stderr)


def quantize_int8(fp32_path: Path, int8_path: Path):
    """Use onnxruntime's dynamic quantizer to produce the q8 artifact."""
    from onnxruntime.quantization import quantize_dynamic, QuantType
    quantize_dynamic(
        model_input=str(fp32_path),
        model_output=str(int8_path),
        weight_type=QuantType.QInt8,
    )
    print(f"[export] int8 → {int8_path} ({int8_path.stat().st_size // (1024*1024)} MB)",
          file=sys.stderr)


def mean_pool(token_embeddings: np.ndarray, attention_mask: np.ndarray) -> np.ndarray:
    mask = attention_mask[..., None].astype(np.float32)
    summed = (token_embeddings * mask).sum(axis=1)
    counts = mask.sum(axis=1).clip(min=1)
    return summed / counts


def embed_with_onnx(onnx_path: Path, encoder_dir: Path, texts: list[str]) -> np.ndarray:
    import onnxruntime as ort
    from transformers import PreTrainedTokenizerFast
    sess = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    # See embed_with_pytorch — SetFit-saved encoder dirs have a tokenizer_class
    # entry AutoTokenizer can't resolve; load tokenizer.json directly.
    tokenizer = PreTrainedTokenizerFast(
        tokenizer_file=str(Path(encoder_dir) / "tokenizer.json"))
    enc = tokenizer(texts, padding=True, truncation=True, max_length=128, return_tensors="np")
    inputs = {
        "input_ids": enc["input_ids"].astype(np.int64),
        "attention_mask": enc["attention_mask"].astype(np.int64),
    }
    # XLM-R MiniLM doesn't use token_type_ids; include it only if expected.
    expected_inputs = {i.name for i in sess.get_inputs()}
    if "token_type_ids" in expected_inputs and "token_type_ids" in enc:
        inputs["token_type_ids"] = enc["token_type_ids"].astype(np.int64)
    out = sess.run(None, inputs)
    token_embs = out[0]
    pooled = mean_pool(token_embs, enc["attention_mask"])
    norms = np.linalg.norm(pooled, axis=1, keepdims=True).clip(min=1e-9)
    return pooled / norms


def embed_with_pytorch(encoder_dir: Path, texts: list[str]) -> np.ndarray:
    """Embed via raw transformers — bypasses sentence_transformers v5's
    AutoProcessor lookup which fails on SetFit-saved encoder dirs (they
    have no processor_config.json; only tokenizer.json + transformer
    weights). Mean-pool + L2-normalize manually to match the bundled
    runtime path (paraphrase-l12 + mean-pool in EmbeddingModel.embed).
    """
    import torch
    from transformers import AutoModel, PreTrainedTokenizerFast
    # SetFit-saved tokenizer_config.json references the sentence_transformers
    # v5 wrapper class `TokenizersBackend` which AutoTokenizer can't resolve.
    # Bypass by loading tokenizer.json directly via PreTrainedTokenizerFast.
    tokenizer = PreTrainedTokenizerFast(
        tokenizer_file=str(Path(encoder_dir) / "tokenizer.json"))
    model = AutoModel.from_pretrained(str(encoder_dir))
    model.eval()
    enc = tokenizer(texts, padding=True, truncation=True, max_length=128,
                    return_tensors="pt")
    with torch.no_grad():
        out = model(**enc)
    token_embs = out.last_hidden_state.numpy()
    pooled = mean_pool(token_embs, enc["attention_mask"].numpy())
    norms = np.linalg.norm(pooled, axis=1, keepdims=True).clip(min=1e-9)
    return pooled / norms


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--encoder", required=True, type=Path,
                    help="SentenceTransformer save dir (output of train_setfit.py).")
    ap.add_argument("--output", required=True, type=Path,
                    help="Path to write the int8 quantized ONNX.")
    ap.add_argument("--quantize", default="int8", choices=["int8", "none"],
                    help="int8 = quantize_dynamic (matches bundled artifact); none = FP32 only.")
    ap.add_argument("--parity-threshold", type=float, default=PARITY_THRESHOLD,
                    help="Min cosine between PyTorch and ONNX embeddings.")
    args = ap.parse_args()

    if not args.encoder.exists():
        print(f"ERROR: encoder dir not found: {args.encoder}", file=sys.stderr)
        sys.exit(2)

    # Stage 1: FP32 ONNX export
    fp32_path = args.output.with_suffix(".fp32.onnx")
    export_fp32(args.encoder, fp32_path)

    # Stage 2: int8 quantization
    if args.quantize == "int8":
        quantize_int8(fp32_path, args.output)
        fp32_path.unlink()  # we don't ship FP32
    else:
        # No quantization — rename fp32 → output
        fp32_path.rename(args.output)

    # Stage 3: parity check between PyTorch original and ONNX export
    print(f"[parity] embedding {len(PARITY_SAMPLES)} samples with both backends...",
          file=sys.stderr)
    pt_embs = embed_with_pytorch(args.encoder, PARITY_SAMPLES)
    onnx_embs = embed_with_onnx(args.output, args.encoder, PARITY_SAMPLES)
    sims = [cosine(p, o) for p, o in zip(pt_embs, onnx_embs)]
    min_sim = float(min(sims))
    mean_sim = float(np.mean(sims))
    print(f"[parity] min={min_sim:.4f} mean={mean_sim:.4f} threshold={args.parity_threshold}",
          file=sys.stderr)
    for text, sim in zip(PARITY_SAMPLES, sims):
        print(f"[parity]   {sim:.4f}  {text[:60]}", file=sys.stderr)
    passed = min_sim >= args.parity_threshold

    result = {
        "export_succeeded": True,
        "parity_min_cosine": min_sim,
        "parity_mean_cosine": mean_sim,
        "parity_threshold": args.parity_threshold,
        "parity_passes": passed,
        "onnx_path": str(args.output),
        "onnx_size_bytes": args.output.stat().st_size,
    }
    print(json.dumps(result))
    if not passed:
        print(f"ERROR: parity {min_sim:.4f} below threshold {args.parity_threshold}",
              file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
