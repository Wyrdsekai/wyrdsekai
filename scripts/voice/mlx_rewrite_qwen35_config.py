#!/usr/bin/env python3
# recipe-callable: local-ok
"""Rewrite a Qwen3.5 HF config.json so mlx-lm 0.31.x can load it.

Newer HF transformers versions tag the Qwen3.5 text-only architecture as
`model_type: "qwen3_5_text"` + `architectures: ["Qwen3_5ForCausalLM"]`
(the `_text` suffix distinguishes it from the multimodal variant). mlx-lm
0.31.x still uses the older `qwen3_5` / `Qwen3_5ForConditionalGeneration`
naming. The underlying tensor layout is identical (same Qwen3.5 base,
same GatedDeltaNet hybrid for 9B via `full_attention_interval=4`), so a
trivial config rewrite bridges them and `mlx_lm.convert` succeeds.

Usage:
    python -m scripts.voice.mlx_rewrite_qwen35_config <hf-dir>

Idempotent. Saves `config.json.original` once (won't overwrite if it
already exists). Re-running on an already-rewritten config is a no-op.

Exit 0 on success (rewrite applied OR not needed).
Exit 2 if hf-dir invalid.
"""
from __future__ import annotations

import json
import shutil
import sys
from pathlib import Path


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print("Usage: python -m scripts.voice.mlx_rewrite_qwen35_config <hf-dir>",
              file=sys.stderr)
        return 2

    hf_dir = Path(argv[1])
    config_path = hf_dir / "config.json"
    if not config_path.is_file():
        print(f"ERROR: {config_path} not found", file=sys.stderr)
        return 2

    with config_path.open() as f:
        config = json.load(f)

    model_type = config.get("model_type")
    arch = config.get("architectures", [])

    needs_rewrite = (
        model_type == "qwen3_5_text"
        or arch == ["Qwen3_5ForCausalLM"]
    )

    if not needs_rewrite:
        print(f"[mlx-rewrite] {config_path}: already qwen3_5 "
              f"(model_type={model_type!r}, arch={arch}) — no change.")
        return 0

    original = config_path.with_suffix(".json.original")
    if not original.exists():
        shutil.copy2(config_path, original)
        print(f"[mlx-rewrite] saved backup to {original}")
    else:
        print(f"[mlx-rewrite] backup at {original} already exists; keeping.")

    config["model_type"] = "qwen3_5"
    config["architectures"] = ["Qwen3_5ForConditionalGeneration"]
    with config_path.open("w") as f:
        json.dump(config, f, indent=2)
    print(f"[mlx-rewrite] rewrote {config_path}: "
          f"model_type=qwen3_5_text→qwen3_5, "
          f"architectures=Qwen3_5ForCausalLM→Qwen3_5ForConditionalGeneration")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
