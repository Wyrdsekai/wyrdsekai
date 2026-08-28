# recipe-callable: local-ok
"""Loader for wyrdsekai HF checkpoints whose exporter wrote a FLAT text
config while stamping the ConditionalGeneration architecture. AutoConfig
therefore builds the nested multimodal skeleton with DEFAULT dims (hidden
4096 vs the checkpoint's 2560) and every tensor "mismatches" — and
transformers' "Reinit due to size mismatch" then silently replaces the
soul's weights with noise. The weights themselves are a plain text causal
LM (model.layers.*): build the TextConfig from the flat dict and load with
the causal class. The dims assert makes a misparse loud, never silent.

Promoted from the fast-sleep probe workdir 2026-08-15 (sleep-forge v2,
sparse-future item 3).o
"Tooling debt paid en route".
"""
import json
import pathlib

import torch
from transformers import Qwen3_5ForCausalLM, Qwen3_5TextConfig

META = {"architectures", "transformers_version", "dtype", "model_type"}


def load_wyrd_model(model_dir, quantization_config=None):
    flat = json.load(open(pathlib.Path(model_dir) / "config.json"))
    cfg = Qwen3_5TextConfig(**{k: v for k, v in flat.items() if k not in META})
    assert cfg.hidden_size == flat["hidden_size"], "config rebuild lost dims"
    return Qwen3_5ForCausalLM.from_pretrained(
        model_dir, config=cfg, quantization_config=quantization_config,
        device_map="auto", torch_dtype=torch.bfloat16)
