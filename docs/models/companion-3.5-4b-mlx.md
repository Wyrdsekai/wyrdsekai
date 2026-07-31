---
license: apache-2.0
base_model: Qwen/Qwen3.5-4B
library_name: mlx
pipeline_tag: text-generation
tags:
  - wyrdsekai
  - companion
  - mlx
language:
  - en
  - es
  - ja
---

# Wyrdsekai Qwen3.5-4B v10 (MLX 4-bit)

MLX 4-bit conversion of the [Wyrdsekai](https://github.com/Wyrdsekai/wyrdsekai) companion voice base,
for Apple Silicon hosts. Pulled automatically by `wyrd setup` on macOS (arm64) when the MLX
voice runtime is bootstrapped; serves on `:8201` via `mlx_lm.server`.

GGUF equivalent for llama.cpp hosts: [wyrdsekai/companion-3.5-4b-gguf](https://huggingface.co/wyrdsekai/companion-3.5-4b-gguf).
Training data: synthetic corpora only (no user conversations).
