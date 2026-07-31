#!/usr/bin/env python3
# recipe-callable: local-ok
"""Convert llama.cpp control-vector GGUFs to MLX-loadable safetensors.

Phase 1A of. macOS voice runtime hooks per-layer
additive vectors into the Qwen3.5+DeltaNet forward pass; this script
produces the on-disk format the runtime loads.

Input  (one per vector, e.g. first_person_presence.gguf):
    GGUF v3, 31 tensors named `direction.1`..`direction.31`, each (H,)
    float32. The N in `direction.N` is the 1-based index of the layer
    whose post-block residual receives the vector (i.e., 0-based
    `model.layers[N-1]` output).

Output (one per vector):
    <out-dir>/<name>.safetensors
        keys: "direction.1".."direction.31", float32 (H,)
        metadata:
            hidden_size       = "<H>"
            layer_count       = "<N>"           (tensor count, may be < 32)
            base_model_hint   = "<gguf model_hint>"
            source_gguf_sha   = "<sha256[:16]>"
    <out-dir>/<name>.meta.json
        same metadata as a sidecar (so loaders without metadata access
        can still read it; mlx_runtime.py uses the safetensors metadata
        path).

This is a pure tensor-shape passthrough — no semantic transform. Index
convention is preserved verbatim so production llama.cpp behavior and
MLX behavior apply the same vector to the same layer.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
LLAMACPP_GGUF_PY = REPO_ROOT.parent / "work_dev" / "llama.cpp" / "gguf-py"
if LLAMACPP_GGUF_PY.exists():
    sys.path.insert(0, str(LLAMACPP_GGUF_PY))


def _sha256_head(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 16), b""):
            h.update(chunk)
    return h.hexdigest()[:16]


def _kv_value(field) -> str | int | float | None:
    """Decode a GGUF KV field. Strings are stored as uint8 byte arrays;
    numerics as 1-element int/float arrays. Use the field's parts dtype
    to disambiguate rather than guessing from list shape."""
    if not field.data:
        return None
    try:
        import numpy as np
        part = field.parts[field.data[0]]
        arr = np.asarray(part)
        if arr.dtype == np.uint8:
            try:
                return bytes(arr.tolist()).decode("utf-8", "replace")
            except Exception:
                return None
        v = arr.tolist()
        if isinstance(v, list):
            return v[0] if len(v) == 1 else v
        return v
    except Exception:
        return None


def convert(gguf_path: Path, out_path: Path) -> dict:
    try:
        from gguf import GGUFReader  # type: ignore
    except ImportError as e:
        raise SystemExit(
            f"[mlx_load_vectors] gguf python package unavailable: {e}. "
            f"Install via `pip install gguf` or run from a venv that "
            f"sees llama.cpp/gguf-py.")
    try:
        import numpy as np
        from safetensors.numpy import save_file
    except ImportError as e:
        raise SystemExit(
            f"[mlx_load_vectors] missing safetensors/numpy: {e}")

    reader = GGUFReader(str(gguf_path))

    model_hint = _kv_value(reader.fields.get("controlvector.model_hint")) \
        if "controlvector.model_hint" in reader.fields else ""
    layer_count_kv = _kv_value(reader.fields.get("controlvector.layer_count")) \
        if "controlvector.layer_count" in reader.fields else None

    tensors = {}
    hidden_size = None
    for t in reader.tensors:
        if not t.name.startswith("direction."):
            continue
        arr = np.array(t.data, dtype=np.float32).reshape(int(t.shape[0]))
        if hidden_size is None:
            hidden_size = int(arr.shape[0])
        elif int(arr.shape[0]) != hidden_size:
            raise SystemExit(
                f"[mlx_load_vectors] inconsistent hidden_size in {gguf_path}: "
                f"saw {hidden_size} and {arr.shape[0]}")
        tensors[t.name] = arr

    if not tensors:
        raise SystemExit(
            f"[mlx_load_vectors] no `direction.*` tensors found in {gguf_path}")

    meta = {
        "hidden_size": str(hidden_size),
        "layer_count": str(layer_count_kv if layer_count_kv is not None
                           else len(tensors)),
        "tensor_count": str(len(tensors)),
        "base_model_hint": str(model_hint or ""),
        "source_gguf": gguf_path.name,
        "source_gguf_sha256_16": _sha256_head(gguf_path),
        "format_version": "1",
    }
    out_path.parent.mkdir(parents=True, exist_ok=True)
    save_file(tensors, str(out_path), metadata=meta)

    sidecar = out_path.with_suffix(".meta.json")
    with sidecar.open("w") as f:
        json.dump(meta, f, indent=2, sort_keys=True)

    return {
        "input": str(gguf_path),
        "output": str(out_path),
        "tensors_written": len(tensors),
        "hidden_size": hidden_size,
        "metadata": meta,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", type=Path,
                    help="Single .gguf path. If absent, use --input-dir.")
    ap.add_argument("--input-dir", type=Path,
                    default=REPO_ROOT / "data" / "training" / "v8" / "vectors",
                    help="Directory of .gguf files to batch-convert.")
    ap.add_argument("--output-dir", type=Path,
                    default=REPO_ROOT / "data" / "training" / "v8" / "mlx",
                    help="Directory to write .safetensors into.")
    ap.add_argument("--vectors", type=str, default="",
                    help="Comma-separated subset of vector names (no .gguf "
                         "extension) when batch-converting. Default = all "
                         ".gguf files in --input-dir.")
    args = ap.parse_args()

    results = []
    if args.input:
        if not args.input.exists():
            raise SystemExit(f"[mlx_load_vectors] not found: {args.input}")
        name = args.input.stem
        out = args.output_dir / f"{name}.safetensors"
        results.append(convert(args.input, out))
    else:
        if not args.input_dir.exists():
            raise SystemExit(
                f"[mlx_load_vectors] input-dir missing: {args.input_dir}")
        wanted = {n.strip() for n in args.vectors.split(",") if n.strip()} \
            if args.vectors else None
        ggufs = sorted(args.input_dir.glob("*.gguf"))
        if not ggufs:
            raise SystemExit(
                f"[mlx_load_vectors] no .gguf files under {args.input_dir}")
        for p in ggufs:
            if wanted is not None and p.stem not in wanted:
                continue
            out = args.output_dir / f"{p.stem}.safetensors"
            results.append(convert(p, out))

    print(json.dumps({"converted": results}, indent=2))


if __name__ == "__main__":
    main()
