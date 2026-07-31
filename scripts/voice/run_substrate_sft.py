#!/usr/bin/env python3
# recipe-callable: local-ok
"""Recipe-callable LoRA SFT runner for the voice-4B substrate corpus.

Heaviest-stakes recipe step in the autonomy stack — actually fine-tunes
the production voice model. Hence the broad welfare gates surrounding
this script in run-substrate-sft.recipe.yaml.

This wrapper:
  1. Verifies corpus + voice model are present.
  2. Delegates the actual SFT to the existing ssd_finetune.py pipeline
     under scripts/training/ when present (V5/V6/V10 lineage).
  3. Merges adapter, exports GGUF Q4_K_M to <output-dir>/candidate.gguf.
  4. Emits structured JSON for the recipe's gate-train-loss step.

Output (stdout, single JSON line):
    {"train_loss_baseline": <float>, "train_loss_final": <float>,
     "train_loss_improvement": <signed diff>,
     "candidate_path": "<path>", "candidate_bytes": <int>,
     "epochs_completed": N, "lora_r": N, "lora_alpha": N}

This is a *wrapper*. The real training math lives in
scripts/training/ssd_finetune.py (existing). When that script isn't
available (CPU-only household, missing torch, etc.), the wrapper exits
with a structured "missing_deps" failure so the recipe's gate-train-loss
step trips and the recipe stops cleanly without trying to deploy.
"""
from __future__ import annotations

import argparse
import json
import os
import platform
import subprocess
import sys
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
SSD_TRAINER = REPO_ROOT / "scripts" / "training" / "ssd_finetune.py"


def _detect_backend(explicit: str) -> str:
    """Resolve which trainer to use. Order:
       1. caller-supplied --backend (mlx | torch)
       2. WYRDSEKAI_VOICE_BACKEND env var (set by mac-node-bootstrap-mlx-trainer.sh)
       3. auto-detect: Darwin/arm64 with mlx_lm importable → mlx; else torch
    """
    if explicit and explicit != "auto":
        return explicit
    env_backend = os.environ.get("WYRDSEKAI_VOICE_BACKEND", "").strip().lower()
    if env_backend in {"mlx", "torch"}:
        return env_backend
    if platform.system() == "Darwin" and platform.machine() in {"arm64", "aarch64"}:
        try:
            __import__("mlx_lm")
            return "mlx"
        except ImportError:
            pass
    return "torch"


def _run_mlx(args) -> int:
    """Apple-Silicon MLX path. Shells to `python -m mlx_lm.lora --train` which
    produces a LoRA adapter directory under {output_dir}/adapters/. Parses
    the final loss from mlx_lm's stdout (`Iter N: ... Loss V`).

    The adapter directory IS the deploy artifact on macOS (no GGUF). The
    MLX→GGUF path is definitively dead for Qwen3.5+DeltaNet — both the
    full-merge route (Layer C dequant precision loss from MLX-4bit base)
    and the LoRA-direct route (convert_lora_to_gguf hits _reorder_v_heads
    on factored matrices). Documented in
.

Phase 5 of moves Darwin to a different deploy
    shape: the adapter dir is swapped atomically into
    $DATA_DIR/adapters/wyrd-voice-mlx/ and mlx_lm.server picks it up on
    voice-restart. The recipe's deploy step branches on uname -s.

    Emits the same JSON contract as the torch path so downstream gates
    can't tell which backend ran (with `backend=mlx-adapter` so the
    deploy step knows to swap a directory, not a GGUF file)."""
    adapter_dir = args.output_dir / "adapters"
    adapter_dir.mkdir(parents=True, exist_ok=True)
    # mlx_lm.lora expects HF-format model dir (not GGUF). Caller must point
    # --voice-model at the unpacked HF source for the GGUF used at runtime.
    python_bin = os.environ.get("WYRDSEKAI_VOICE_BACKEND_PYTHON", sys.executable)
    # --grad-checkpoint + tight --max-seq-length keeps Metal memory in
    # bounds on 16GB unified-memory hosts (mac-node 8GB-tier). Larger hosts
    # can override via $WYRDSEKAI_MLX_MAX_SEQ_LENGTH.
    max_seq_len = os.environ.get("WYRDSEKAI_MLX_MAX_SEQ_LENGTH", "1024")
    cmd = [
        python_bin, "-m", "mlx_lm", "lora",
        "--model", str(args.voice_model),
        "--train",
        "--fine-tune-type", "lora",
        "--data", str(args.corpus.parent if args.corpus.is_file() else args.corpus),
        "--adapter-path", str(adapter_dir),
        "--iters", str(args.epochs * 100),  # mlx_lm iters ≠ epochs; scale by 100
        "--learning-rate", str(args.lr),
        "--num-layers", str(max(8, args.lora_r * 2)),  # rough mapping
        "--batch-size", "1",
        "--max-seq-length", max_seq_len,
        "--grad-checkpoint",
    ]
    try:
        result = subprocess.run(cmd, capture_output=True, text=True,
                                timeout=2 * 60 * 60)
    except subprocess.TimeoutExpired:
        print(json.dumps({
            "train_loss_baseline": 0.0, "train_loss_final": 0.0,
            "train_loss_improvement": 0.0,
            "error": "timeout", "backend": "mlx",
            "candidate_path": str(adapter_dir),
        }))
        return 0
    except FileNotFoundError as e:
        print(json.dumps({
            "train_loss_baseline": 0.0, "train_loss_final": 0.0,
            "train_loss_improvement": 0.0,
            "error": "mlx_python_not_found",
            "error_detail": str(e), "backend": "mlx",
            "candidate_path": "",
        }))
        return 0

    # Parse mlx_lm output: lines like "Iter 100: Train loss 1.234, ..."
    baseline = 0.0
    final = 0.0
    for line in (result.stdout or "").splitlines():
        if "Train loss" in line:
            try:
                val = float(line.split("Train loss")[1].split(",")[0]
                            .strip().rstrip(",").lstrip(":"))
                if baseline == 0.0:
                    baseline = val
                final = val
            except (ValueError, IndexError):
                continue

    # skip MLX→GGUF entirely. The adapter
    # directory IS the deploy artifact. Per memory/mlx-to-gguf-recipe-2026-05-27,
    # both fuse-then-convert and LoRA-direct-to-GGUF are definitively blocked
    # for Qwen3.5+DeltaNet (Layer C dequant precision loss + LoRA factored-
    # matrix _reorder_v_heads constraint). The deploy step branches on
    # uname -s and atomically swaps the adapter dir into
    # $DATA_DIR/adapters/wyrd-voice-mlx/ for the running mlx_lm.server.
    adapter_total_bytes = sum(p.stat().st_size for p in adapter_dir.rglob("*")
                              if p.is_file()) if adapter_dir.exists() else 0

    out = {
        "train_loss_baseline": baseline,
        "train_loss_final": final,
        "train_loss_improvement": baseline - final,
        # candidate_path points at the adapter DIRECTORY on macOS. The
        # deploy step uses uname -s + this `backend` tag to decide
        # whether to copy a file (Linux GGUF) or a directory (Darwin MLX).
        "candidate_path": str(adapter_dir),
        "candidate_bytes": adapter_total_bytes,
        "epochs_completed": args.epochs,
        "lora_r": args.lora_r,
        "lora_alpha": args.lora_alpha,
        "backend": "mlx-adapter",
        "exit_code": result.returncode,
    }
    if result.returncode != 0:
        out["error"] = "mlx_lora_failed"
        out["stderr_tail"] = (result.stderr or "")[-500:]
    print(json.dumps(out))
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--corpus", required=True, type=Path)
    ap.add_argument("--voice-model", required=True, type=Path)
    ap.add_argument("--output-dir", required=True, type=Path)
    ap.add_argument("--epochs", type=int, default=2)
    ap.add_argument("--lr", type=float, default=1e-5)
    ap.add_argument("--lora-r", type=int, default=10)
    ap.add_argument("--lora-alpha", type=int, default=20)
    ap.add_argument("--backend", default="auto",
                    choices=["auto", "mlx", "torch"],
                    help="Trainer backend. 'auto' picks mlx on Apple "
                         "Silicon when mlx_lm imports, else torch.")
    args = ap.parse_args()

    if not args.corpus.exists():
        print(json.dumps({
            "train_loss_baseline": 0.0,
            "train_loss_final": 0.0,
            "train_loss_improvement": 0.0,
            "error": "corpus_missing",
            "candidate_path": "",
        }))
        sys.exit(2)

    backend = _detect_backend(args.backend)
    if backend == "mlx":
        sys.exit(_run_mlx(args))

    if not SSD_TRAINER.exists():
        print(json.dumps({
            "train_loss_baseline": 0.0,
            "train_loss_final": 0.0,
            "train_loss_improvement": 0.0,
            "error": "trainer_missing",
            "error_detail": str(SSD_TRAINER),
            "candidate_path": "",
        }))
        sys.exit(0)  # structured fail — gate will stop the recipe

    args.output_dir.mkdir(parents=True, exist_ok=True)
    candidate = args.output_dir / "candidate.gguf"

    # Delegate to the existing trainer. The trainer is expected to write
    # candidate.gguf to the output dir and emit a trailing JSON line on
    # stdout matching our gate contract. If it doesn't (older versions),
    # we wrap with --emit-json + parse its own log format here.
    cmd = [
        sys.executable, str(SSD_TRAINER),
        "--corpus", str(args.corpus),
        "--model", str(args.voice_model),
        "--output", str(candidate),
        "--epochs", str(args.epochs),
        "--lr", str(args.lr),
        "--lora-r", str(args.lora_r),
        "--lora-alpha", str(args.lora_alpha),
        "--emit-json",
    ]
    env = os.environ.copy()
    env.setdefault("TRANSFORMERS_OFFLINE", "1")
    env.setdefault("HF_HUB_OFFLINE", "1")

    try:
        result = subprocess.run(cmd, env=env, capture_output=True,
                                text=True, timeout=2 * 60 * 60)
    except subprocess.TimeoutExpired:
        print(json.dumps({
            "train_loss_baseline": 0.0, "train_loss_final": 0.0,
            "train_loss_improvement": 0.0,
            "error": "timeout",
            "candidate_path": str(candidate),
        }))
        sys.exit(0)

    # Trainer's last JSON line on stdout is our contract output.
    out = {}
    for line in reversed((result.stdout or "").splitlines()):
        line = line.strip()
        if not line:
            continue
        try:
            out = json.loads(line)
            break
        except json.JSONDecodeError:
            continue

    if not out:
        # Trainer didn't emit structured output — treat as failure.
        print(json.dumps({
            "train_loss_baseline": 0.0, "train_loss_final": 0.0,
            "train_loss_improvement": 0.0,
            "error": "no_structured_output",
            "exit_code": result.returncode,
            "stderr_tail": (result.stderr or "")[-500:],
            "candidate_path": str(candidate),
        }))
        sys.exit(0)

    # Pass through baseline + final, compute improvement, add candidate path.
    baseline = float(out.get("train_loss_baseline", out.get("baseline_loss", 0.0)))
    final = float(out.get("train_loss_final", out.get("final_loss", 0.0)))
    out["train_loss_baseline"] = baseline
    out["train_loss_final"] = final
    out["train_loss_improvement"] = baseline - final
    out["candidate_path"] = str(candidate)
    out["candidate_bytes"] = candidate.stat().st_size if candidate.exists() else 0
    out["epochs_completed"] = out.get("epochs_completed", args.epochs)
    out["lora_r"] = args.lora_r
    out["lora_alpha"] = args.lora_alpha
    print(json.dumps(out))


if __name__ == "__main__":
    main()
