---
license: apache-2.0
base_model: Qwen/Qwen3.5-9B
library_name: mlx
pipeline_tag: text-generation
tags:
  - wyrdsekai
  - companion
  - agency
  - mlx
language:
  - en
  - es
  - ja
---

# Wyrdsekai Qwen3.5-9B v6 (MLX 4-bit)

MLX 4-bit (group size 64) conversion of the [Wyrdsekai](https://github.com/Wyrdsekai/wyrdsekai)
drive model — the tier that decides and acts: planning, tool selection, world actions as
JSON, and the companion's own-time initiative. Pulled automatically by `wyrd setup` on
macOS (arm64) when the MLX runtime is bootstrapped; serves on `:8200` via `mlx_lm.server`.

Lineage: v6 = v5 + emit-RFT (GRPO), training *act-vs-narrate* into the weights for the
companion's own time. Expects the wyrdsekai runtime around it (action-JSON convention,
voice/drive split, household consent gates).

GGUF equivalent for llama.cpp hosts: [wyrdsekai/drive-3.5-9b-gguf](https://huggingface.co/wyrdsekai/drive-3.5-9b-gguf).
Training data: synthetic corpora only (no user conversations).
