#!/usr/bin/env python3
"""Build the training bundle the single-model night's write reads (brain_write.py).

Maintainer tool, run once per base model on a machine that can hold the bf16 checkpoint on its
GPUs. A household downloads the result; it does not run this.

The bundle is: spine.safetensors (every weight that is not a routed expert, bf16),
experts_L<i>.safetensors (each layer's routed experts, NF4 with double quantisation),
config.json (the text model's config, for the trainer), hf/config.json (the checkpoint's own
config, for the adapter converter), tok/ (tokenizer files), bundle.json (what it was made from).

Usage: brain_bundle.py <hf_checkpoint_dir> <out_dir>
"""
import hashlib
import json
import os
import shutil
import sys
import time

import torch
import bitsandbytes.functional as BF
from safetensors.torch import save_file
from transformers import AutoModelForCausalLM
from transformers.models.qwen3_5_moe.modeling_qwen3_5_moe import Qwen3_5MoeExperts

hf, out = sys.argv[1:3]
os.makedirs(os.path.join(out, "hf"), exist_ok=True); os.makedirs(os.path.join(out, "tok"), exist_ok=True)
t0 = time.time(); n = torch.cuda.device_count()
model = AutoModelForCausalLM.from_pretrained(hf, dtype=torch.bfloat16, device_map="auto",
            max_memory={i: "38GiB" for i in range(n)}, low_cpu_mem_usage=True).eval()
print(f"[bundle] loaded {type(model).__name__} on {n} GPU(s) in {time.time()-t0:.0f}s", flush=True)
model.config.save_pretrained(out)
shutil.copyfile(os.path.join(hf, "config.json"), os.path.join(out, "hf", "config.json"))
for f in ("tokenizer.json", "tokenizer_config.json", "vocab.json", "merges.txt", "chat_template.jinja"):
    if os.path.isfile(os.path.join(hf, f)): shutil.copyfile(os.path.join(hf, f), os.path.join(out, "tok", f))

layers = 0; expert_prefixes = []
for name, mod in model.named_modules():
    if not isinstance(mod, Qwen3_5MoeExperts): continue
    sd = {}
    for wn in ("gate_up_proj", "down_proj"):
        packed, qs = BF.quantize_4bit(getattr(mod, wn).data, blocksize=64, quant_type="nf4", compress_statistics=True)
        sd[wn + ".packed"] = packed.cpu().contiguous()
        for k, v in qs.as_dict(packed=True).items(): sd[wn + "." + k] = v.cpu().contiguous()
    save_file(sd, os.path.join(out, f"experts_L{layers}.safetensors"), metadata={"module": name})
    expert_prefixes.append(name + "."); layers += 1
    time.sleep(1.0)                                      # paced: keeps device-to-host traffic low
spine = {k: v.detach().cpu().contiguous() for k, v in model.state_dict().items()
         if not any(k.startswith(p) for p in expert_prefixes)}
spine_path = os.path.join(out, "spine.safetensors"); save_file(spine, spine_path)
h = hashlib.sha256()
with open(spine_path, "rb") as f:
    for chunk in iter(lambda: f.read(1 << 24), b""): h.update(chunk)
json.dump({"class": type(model).__name__, "layers": layers, "base": os.path.basename(os.path.normpath(hf)),
           "spine_sha256": h.hexdigest(), "experts": "nf4-double-quant-block64"},
          open(os.path.join(out, "bundle.json"), "w"), indent=1)
print(f"[bundle] {layers} expert files + spine ({sum(v.numel() for v in spine.values())/1e9:.2f}B) in {time.time()-t0:.0f}s", flush=True)
