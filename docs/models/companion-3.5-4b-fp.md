---
license: apache-2.0
base_model: Qwen/Qwen3.5-4B
pipeline_tag: text-generation
tags:
  - wyrdsekai
  - companion
  - voice
---

# wyrdsekai companion 3.5-4B v10 (full precision)

Full-precision safetensors of the [wyrdsekai](https://github.com/Wyrdsekai/wyrdsekai)
voice model, v10 — the source the deployment conversions come from:
[GGUF Q4_K_M](https://huggingface.co/wyrdsekai/companion-3.5-4b-gguf) for llama.cpp,
[MLX 4-bit](https://huggingface.co/wyrdsekai/companion-3.5-4b-mlx) for Apple Silicon.
Published for re-quantization to other formats, further fine-tuning, and
full-precision evaluation.

See the [GGUF card](https://huggingface.co/wyrdsekai/companion-3.5-4b-gguf) for
intended use and limitations. Training data: synthetic corpora only — no
end-user or household conversation data. SFT corpus for the drive line is
published at [wyrdsekai/drive-sft-corpus](https://huggingface.co/datasets/wyrdsekai/drive-sft-corpus).
