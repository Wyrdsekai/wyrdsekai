---
license: apache-2.0
base_model: Qwen/Qwen3.5-9B
pipeline_tag: text-generation
tags:
  - wyrdsekai
  - companion
  - agency
---

# wyrdsekai drive 3.5-9B v6 (full precision)

Full-precision safetensors of the [wyrdsekai](https://github.com/Wyrdsekai/wyrdsekai)
drive model, v6 — the source the deployment conversions come from:
[GGUF Q4_K_M](https://huggingface.co/wyrdsekai/drive-3.5-9b-gguf) for llama.cpp,
[MLX 4-bit](https://huggingface.co/wyrdsekai/drive-3.5-9b-mlx) for Apple Silicon.
Published for re-quantization, further fine-tuning, and full-precision evaluation.
Provenance: this checkpoint was verified by hash chain against the shipped GGUF's
build directory.

See the [GGUF card](https://huggingface.co/wyrdsekai/drive-3.5-9b-gguf) for
lineage (v5 + emit-RFT), intended use and limitations. Training data: synthetic
corpora only — the SFT line's data is published at
[wyrdsekai/drive-sft-corpus](https://huggingface.co/datasets/wyrdsekai/drive-sft-corpus).
