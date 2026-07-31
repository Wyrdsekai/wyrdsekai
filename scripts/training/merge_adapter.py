#!/usr/bin/env python3
"""
Merge an HF LoRA adapter into a base HF model checkpoint.

Used once per agent-substrate to produce the drive-adapted baseline that
VoiceAligner trains against. Philosophy: voice alignment layers on top of
what the agent already is (drive/balanced substrate), not on generic base.

Usage:
  merge_adapter.py --base <base_hf_dir> --adapter <adapter_dir> --out <merged_hf_dir>
"""
import argparse
import os
import shutil
import sys
from pathlib import Path


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True, help="HF-format base model dir")
    ap.add_argument("--adapter", required=True, help="PEFT LoRA adapter dir")
    ap.add_argument("--out", required=True, help="Output dir for merged HF model")
    args = ap.parse_args()

    base = Path(args.base).resolve()
    adapter = Path(args.adapter).resolve()
    out = Path(args.out).resolve()
    if out.exists() and any(out.iterdir()):
        print(f"[merge] refusing to overwrite non-empty {out}", file=sys.stderr)
        sys.exit(2)
    out.mkdir(parents=True, exist_ok=True)

    # Lazy imports: keep error message useful if deps missing
    try:
        import torch
        from transformers import AutoModelForCausalLM, AutoTokenizer
        from peft import PeftModel
    except ImportError as e:
        print(f"[merge] missing dep: {e}", file=sys.stderr)
        sys.exit(3)

    print(f"[merge] loading base from {base}", file=sys.stderr)
    model = AutoModelForCausalLM.from_pretrained(
        str(base), torch_dtype=torch.bfloat16, device_map="auto")
    tokenizer = AutoTokenizer.from_pretrained(str(base))

    print(f"[merge] attaching adapter {adapter}", file=sys.stderr)
    model = PeftModel.from_pretrained(model, str(adapter))

    print("[merge] merge_and_unload...", file=sys.stderr)
    model = model.merge_and_unload()

    print(f"[merge] saving merged to {out}", file=sys.stderr)
    model.save_pretrained(str(out))
    tokenizer.save_pretrained(str(out))

    # Copy over tokenizer / chat template files if adapter provided them
    for fname in ("chat_template.jinja", "tokenizer_config.json",
                    "tokenizer.json"):
        src = adapter / fname
        if src.exists() and not (out / fname).exists():
            shutil.copy2(src, out / fname)
            print(f"[merge] copied {fname} from adapter", file=sys.stderr)

    print(f"[merge] DONE {out}", file=sys.stderr)


if __name__ == "__main__":
    main()
