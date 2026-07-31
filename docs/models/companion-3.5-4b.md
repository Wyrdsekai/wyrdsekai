---
license: apache-2.0
base_model: Qwen/Qwen3.5-4B
pipeline_tag: text-generation
tags:
  - wyrdsekai
  - companion
  - voice
  - gguf
---

# wyrdsekai companion 3.5-4B (v10)

The **voice model** of a [wyrdsekai](https://wyrdsekai.org) household — the tier
that speaks. Every user-facing line a companion says is authored or finished by
this model, so that a household's companions each keep a distinct spoken voice
even when the heavier drive tier did the thinking. In a standard install it
serves on `:8201` (`wyrdsekai-3.5-4b-v10-q4km.gguf`); on phones it is the
recommended on-device model (validated ~5 tok/s on Snapdragon 8 Elite,
`--think=false` required).

Weights on Hugging Face (Apache-2.0, all tagged `v0.1.0`):
[GGUF Q4_K_M](https://huggingface.co/wyrdsekai/companion-3.5-4b-gguf) (what installs run) ·
[full-precision safetensors](https://huggingface.co/wyrdsekai/companion-3.5-4b) ·
[MLX 4-bit](https://huggingface.co/wyrdsekai/companion-3.5-4b-mlx) (Apple Silicon).
`wyrd setup` fetches the GGUF for you — these links are for mirrors,
re-quantization, and fine-tuning.

## What it is tuned for

Speech register, warmth without sycophancy, and fidelity to per-companion voice
profiles (v10 pairs with the V8 voice-vector set). It runs with a deliberately
small prompt — the heavy layers (tools, memory, catalogs) belong to the drive
tier — so it is tuned to say true things briefly rather than to act.

## Lineage

v10 is the production voice line, paired with V8 voice vectors. Earlier voice
versions established the register and the fact-preserving polish behaviour
(structured confirmations must survive rephrasing).

## Training data

Synthetic corpora: authored register/style sets and synthetic dialogues
generated with Claude (Anthropic Batch API). **No end-user or household
conversation data is in these weights.** Per-companion voice individuation
happens locally, on the household's node, and never leaves it.

## Limitations

- Quantized Q4_K_M; full-precision weights are not currently published.
- A speech tier, not an agent: it ships without tools by design in wyrdsekai,
  and is not tuned for tool selection or planning.
- `--think=false` is required on constrained devices.
- English-dominant; multilingual lines occur but are not gated.

## License

Apache 2.0, inherited cleanly from the Qwen3.5 base and applied to this
derivative. Attribution to the base model is retained.
