#!/usr/bin/env python
"""merge the selected GRPO adapter onto the warmed5 base
(peft merge_and_unload) into a standalone HF model, ready for GGUF conversion.

Pure deterministic build step: CPU bf16 load (no GPU needed for a weight merge),
safetensors out. Output dir feeds llama.cpp convert_hf_to_gguf.py -> Q4_K_M.
"""
import argparse, torch
from peft import PeftModel
from transformers import AutoModelForCausalLM, AutoTokenizer

ap = argparse.ArgumentParser()
ap.add_argument("--base", required=True)
ap.add_argument("--adapter", required=True)
ap.add_argument("--out", required=True)
a = ap.parse_args()

print(f"[merge] load base {a.base} (bf16, cpu)", flush=True)
base = AutoModelForCausalLM.from_pretrained(a.base, torch_dtype=torch.bfloat16, device_map="cpu")
print(f"[merge] apply adapter {a.adapter}", flush=True)
m = PeftModel.from_pretrained(base, a.adapter)
print("[merge] merge_and_unload", flush=True)
m = m.merge_and_unload()
print(f"[merge] save merged -> {a.out}", flush=True)
m.save_pretrained(a.out, safe_serialization=True)
AutoTokenizer.from_pretrained(a.base).save_pretrained(a.out)
print("[merge] DONE", flush=True)
