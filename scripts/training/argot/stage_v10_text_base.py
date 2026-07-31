#!/usr/bin/env python3
"""
Stage a text-only HF dir from the v10 4B *merged* checkpoint so llama.cpp's
convert_hf_to_gguf.py can build a GGUF base for it.

WHY THIS EXISTS
---------------
The merged v10 4B dir (``wyrdsekai-4b-v10-merged``) is shaped for the multimodal
runtime: its active ``config.json`` declares ``Qwen3_5ForConditionalGeneration``
(4096 hidden + a vision tower) while the *weights* are the text-only 2560-hidden
model stored under a ``model.language_model.`` prefix. llama.cpp's text converter
(``Qwen3_5ForCausalLM`` / ``qwen3_5_text``) expects plain ``model.layers.N...``
keys and the text-only config. This script bridges the two by:

  1. copying ``config.json.original`` (the text-only ``Qwen3_5ForCausalLM`` config
     the merge step left behind) in as ``config.json``;
  2. re-saving ``model.safetensors`` with every ``model.language_model.`` prefix
     rewritten to ``model.``;
  3. copying the tokenizer / generation / chat-template files verbatim.

``tie_word_embeddings`` is True, so there is no separate ``lm_head`` to handle.

The same in-memory transform is what ``train_argot_lora.load_base_model`` does to
load the model for PEFT; this script just persists it to disk for GGUF conversion.

USAGE
-----
    python stage_v10_text_base.py \
        --src  ~/models/wyrdsekai-4b-v10-merged \
        --dst  ~/models/wyrdsekai-4b-v10-text

Then convert with llama.cpp:
    python convert_hf_to_gguf.py <dst> --outfile <out>.f16.gguf --outtype f16
    llama-quantize <out>.f16.gguf <out>.q4km.gguf Q4_K_M
"""
import argparse
import os
import shutil

from safetensors import safe_open
from safetensors.torch import save_file

PREFIX = "model.language_model."


def stage(src: str, dst: str) -> None:
    os.makedirs(dst, exist_ok=True)

    # 1) text-only config (Qwen3_5ForCausalLM / qwen3_5_text)
    orig = os.path.join(src, "config.json.original")
    if not os.path.exists(orig):
        raise SystemExit(
            f"missing {orig} — the merge step should have written the text-only "
            "config alongside the multimodal one"
        )
    shutil.copy(orig, os.path.join(dst, "config.json"))

    # 2) tokenizer + aux (verbatim)
    for fn in (
        "tokenizer.json",
        "tokenizer_config.json",
        "generation_config.json",
        "chat_template.jinja",
        "special_tokens_map.json",
        "vocab.json",
        "merges.txt",
    ):
        s = os.path.join(src, fn)
        if os.path.exists(s):
            shutil.copy(s, os.path.join(dst, fn))

    # 3) strip the model.language_model. prefix off every tensor
    st = os.path.join(src, "model.safetensors")
    tensors = {}
    with safe_open(st, framework="pt") as f:
        meta = f.metadata() or {}
        for k in f.keys():
            nk = ("model." + k[len(PREFIX):]) if k.startswith(PREFIX) else k
            tensors[nk] = f.get_tensor(k)

    if "model.embed_tokens.weight" not in tensors:
        raise SystemExit("embed_tokens missing after prefix strip — wrong prefix?")
    if "model.norm.weight" not in tensors:
        raise SystemExit("final norm missing after prefix strip — wrong prefix?")

    out_meta = {"format": "pt", **{k: v for k, v in meta.items() if k != "format"}}
    save_file(tensors, os.path.join(dst, "model.safetensors"), metadata=out_meta)
    print(f"staged {len(tensors)} tensors -> {dst}/model.safetensors")
    print("config:", os.path.join(dst, "config.json"))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", required=True, help="merged v10 4B HF dir")
    ap.add_argument("--dst", required=True, help="output text-only HF dir")
    args = ap.parse_args()
    stage(args.src, args.dst)


if __name__ == "__main__":
    main()
