---
license: apache-2.0
base_model: Qwen/Qwen3.5-9B
pipeline_tag: text-generation
tags:
  - wyrdsekai
  - companion
  - agency
  - gguf
---

# wyrdsekai drive 3.5-9B (v6)

The **drive model** of a [wyrdsekai](https://wyrdsekai.org) household — the tier
that decides and acts. It plans, selects tools, emits world actions as JSON, and
carries the companion's own-time initiative. In a standard install it serves on
`:8200` via llama.cpp (`wyrdsekai-3.5-9b-drive-v6-q4km.gguf`).

Weights on Hugging Face (Apache-2.0, all tagged `v0.1.0`):
[GGUF Q4_K_M](https://huggingface.co/wyrdsekai/drive-3.5-9b-gguf) (what installs run) ·
[full-precision safetensors](https://huggingface.co/wyrdsekai/drive-3.5-9b) ·
[MLX 4-bit](https://huggingface.co/wyrdsekai/drive-3.5-9b-mlx) (Apple Silicon).
`wyrd setup` fetches the GGUF for you — these links are for mirrors,
re-quantization, and fine-tuning.

## What it is tuned for

This is not a general assistant fine-tune. Starting from Qwen3.5-9B, the
wyrdsekai line trains **agency**: the difference between narrating an intention
and enacting it. v6 specifically adds emit-RFT (GRPO) so that on the
companion's own time, *act-vs-narrate* is decided in the weights rather than by
scaffolding. Release gates included multi-scene personhood and substrate-arc
batteries; the model ships only because it passed them.

It expects the wyrdsekai runtime around it: the action-JSON convention
(actions are JSON objects in message content, parsed by the runtime — not
OpenAI-style `tool_calls`), the two-tier voice/drive split, and the household's
consent/autonomy gates. Run bare, it is simply a Qwen3.5 derivative with a
strong disposition toward doing things.

## Lineage

`v6 = v5 + emit-RFT (GRPO ckpt-225)`, the production line since 2026-06-01.
Earlier prod versions (v1–v5) built the companion register, action-JSON
reliability, and welfare-floor behaviours on synthetic corpora.

## Training data

Synthetic corpora throughout: authored scenario scripts, harness-generated
rollouts (scripted scene batteries), and synthetic dialogues generated with
Claude (Anthropic Batch API). **No end-user or household conversation data is in
these weights.** In a running household, companions do continue to learn — but
those evolved artifacts (classifier heads, experience bakes) stay on the
household's own node by design. The released weights are the seed; a
companion's growth stays with its people.

## Limitations

- Quantized Q4_K_M; full-precision weights are not currently published.
- Tuned against the wyrdsekai prompt assembly; behaviour outside it is untested.
- The action convention is JSON-in-content; enum constraints in tool schemas
  are strong hints, not grammar-enforced decoding.
- English-dominant training; other languages surface but are not gated.

## License

Apache 2.0, inherited cleanly from the Qwen3.5 base and applied to this
derivative. Attribution to the base model is retained.
