#!/usr/bin/env python3
"""
Convert an MLX-LM LoRA adapter to PEFT (HuggingFace) format.

mlx-lm's `mlx_lm.lora --train` writes an adapter as:
    <adapter-path>/adapters.safetensors
    <adapter-path>/adapter_config.json   (mlx-style)

llama.cpp's `convert_lora_to_gguf.py` expects PEFT format:
    <adapter-path>/adapter_model.safetensors
    <adapter-path>/adapter_config.json   (peft-style, peft_type="LORA")

The state-dict keys also differ:
    mlx :  model.layers.0.self_attn.q_proj.lora_a / lora_b
    peft:  base_model.model.model.layers.0.self_attn.q_proj.lora_A.weight
                                                                   .lora_B.weight

This script translates the file in-place: writes adapter_model.safetensors
alongside the existing adapters.safetensors and rewrites adapter_config.json
to PEFT shape. Original mlx files are kept (safe to re-run).

Usage: mlx_adapter_to_peft.py <adapter-path>
Returns 0 on success, 2 if adapter dir/files missing, 1 on conversion error.
"""
import json
import sys
from pathlib import Path

USAGE = "usage: mlx_adapter_to_peft.py <adapter-path>"


def fail(msg, code=1):
    print(f"[mlx-to-peft] ERROR: {msg}", file=sys.stderr)
    sys.exit(code)


def main(argv):
    if len(argv) != 2:
        fail(USAGE)
    adapter_dir = Path(argv[1])
    if not adapter_dir.is_dir():
        fail(f"not a directory: {adapter_dir}", code=2)

    src_st = adapter_dir / "adapters.safetensors"
    src_cfg = adapter_dir / "adapter_config.json"
    if not src_st.exists():
        fail(f"missing {src_st}", code=2)

    try:
        from safetensors.torch import load_file, save_file
    except ImportError:
        try:
            # Fallback: mlx-bundled safetensors (no torch needed) + numpy.
            from safetensors import safe_open
            from safetensors.numpy import save_file as np_save_file
            import numpy as np

            tensors = {}
            with safe_open(str(src_st), framework="np") as f:
                for k in f.keys():
                    tensors[k] = f.get_tensor(k)
            renamed = {}
            for k, v in tensors.items():
                new_key = rename_key(k)
                renamed[new_key] = transpose_for_peft(new_key, v)
            out = adapter_dir / "adapter_model.safetensors"
            np_save_file(renamed, str(out))
            print(f"[mlx-to-peft] wrote {out} (numpy backend, {len(renamed)} tensors)")
        except ImportError as e:
            fail(f"need safetensors installed (pip install safetensors): {e}")
    else:
        # torch path: works wherever unsloth/torch already lives
        tensors = load_file(str(src_st))
        renamed = {}
        for k, v in tensors.items():
            new_key = rename_key(k)
            renamed[new_key] = transpose_for_peft(new_key, v)
        out = adapter_dir / "adapter_model.safetensors"
        save_file(renamed, str(out))
        print(f"[mlx-to-peft] wrote {out} (torch backend, {len(renamed)} tensors)")

    # Translate adapter_config.json to PEFT shape if not already there.
    cfg = {}
    if src_cfg.exists():
        try:
            cfg = json.loads(src_cfg.read_text())
        except json.JSONDecodeError:
            cfg = {}

    # PEFT-shape keys we need:
    peft_cfg = {
        "peft_type": "LORA",
        "r": cfg.get("r", cfg.get("lora_parameters", {}).get("rank", 8)),
        "lora_alpha": cfg.get("alpha",
            cfg.get("lora_parameters", {}).get("alpha", 16)),
        "lora_dropout": cfg.get("dropout",
            cfg.get("lora_parameters", {}).get("dropout", 0.0)),
        "target_modules": cfg.get("target_modules",
            ["q_proj", "k_proj", "v_proj", "o_proj",
             "gate_proj", "up_proj", "down_proj"]),
        "bias": "none",
        "task_type": "CAUSAL_LM",
        "fan_in_fan_out": False,
    }
    # Keep base_model_name_or_path if mlx wrote one — convert_lora_to_gguf
    # uses it to locate the base for tensor-shape inference.
    if "base_model_name_or_path" in cfg:
        peft_cfg["base_model_name_or_path"] = cfg["base_model_name_or_path"]

    src_cfg.rename(adapter_dir / "adapter_config.mlx.json")  # preserve original
    (adapter_dir / "adapter_config.json").write_text(json.dumps(peft_cfg, indent=2))
    print(f"[mlx-to-peft] wrote PEFT-style adapter_config.json (rank={peft_cfg['r']})")


def transpose_for_peft(peft_key: str, tensor):
    """
    MLX writes lora weights with the *transposed* layout vs PEFT:
        mlx  lora_a: (in_features, r)        peft lora_A: (r, in_features)
        mlx  lora_b: (r, out_features)       peft lora_B: (out_features, r)
    Without this swap, llama.cpp's convert_lora_to_gguf.py asserts on
    `A.shape[-2] == B.shape[-1]` and bails. Works for both numpy arrays
    and torch tensors (both expose .T / transpose).
    """
    if peft_key.endswith(".lora_A.weight") or peft_key.endswith(".lora_B.weight"):
        transposed = tensor.T if hasattr(tensor, "T") else tensor.transpose(0, 1)
        # torch's .T produces a non-contiguous view; safetensors refuses to
        # serialize that. .contiguous() forces a copy with packed memory.
        # numpy is contiguous after .T for 2D arrays, but np.ascontiguousarray
        # is harmless if so.
        if hasattr(transposed, "contiguous"):     # torch tensor
            return transposed.contiguous()
        try:                                       # numpy fallback
            import numpy as np
            return np.ascontiguousarray(transposed)
        except ImportError:
            return transposed
    return tensor


def rename_key(k: str) -> str:
    """
    Translate an mlx-lm adapter key to PEFT convention.

      model.layers.0.self_attn.q_proj.lora_a → base_model.model.model.layers.0.self_attn.q_proj.lora_A.weight
      model.layers.0.self_attn.q_proj.lora_b → base_model.model.model.layers.0.self_attn.q_proj.lora_B.weight
    """
    out = k
    # mlx omits the trailing ".weight" — PEFT requires it.
    if not out.endswith(".weight"):
        out = out + ".weight"
    # Case rename: lora_a/lora_b → lora_A/lora_B (PEFT is case-sensitive).
    out = out.replace(".lora_a.", ".lora_A.").replace(".lora_b.", ".lora_B.")
    out = out.replace(".lora_a.weight", ".lora_A.weight")
    out = out.replace(".lora_b.weight", ".lora_B.weight")
    # PEFT prefixes the entire base model graph with base_model.model.
    if not out.startswith("base_model.model."):
        out = "base_model.model." + out
    return out


if __name__ == "__main__":
    main(sys.argv)
