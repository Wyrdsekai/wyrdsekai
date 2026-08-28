#!/usr/bin/env python3
# recipe-callable: local-ok
"""Sleep-forge step 2 (nightly tier): one sleep's worth of micro-LoRA.

Deliberately sized like a consolidation, not a finetune: tiny rank,
conservative LR, a few passes over one sleep's corpus. §4o measured the
whole recipe at ~11 minutes on a household-class 16GB card for ~194k
tokens; the write generalized to the companion's unseen day (−0.166 NLL)
without moving neutral text (−0.002). This trains the SPINE tier of the
two-tier soul: voice/identity, nightly, megabytes.

Usage:
  train_spine_lora.py --model DIR --corpus TRAIN.txt --out DIR
      [--epochs 2.0] [--rank 8] [--block 1024]

Logs go to stderr; the LAST stdout line is a JSON summary (the recipe
runner merges stdout JSON into RecipeContext — stdout purity matters).
"""
import argparse
import json
import pathlib
import sys
import time

import torch
from torch.utils.data import Dataset
from transformers import (AutoTokenizer, BitsAndBytesConfig,
                          DataCollatorForLanguageModeling, Trainer,
                          TrainingArguments)
from peft import LoraConfig, get_peft_model

sys.path.insert(0, str(pathlib.Path(__file__).parent))
from wyrd_load import load_wyrd_model  # noqa: E402


def log(msg):
    print(msg, file=sys.stderr, flush=True)


class Blocks(Dataset):
    def __init__(self, ids, block):
        n = (len(ids) // block) * block
        self.blocks = [ids[i:i + block] for i in range(0, n, block)]

    def __len__(self):
        return len(self.blocks)

    def __getitem__(self, i):
        t = torch.tensor(self.blocks[i])
        return {"input_ids": t, "labels": t.clone()}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--corpus", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--epochs", type=float, default=2.0)
    ap.add_argument("--rank", type=int, default=8)
    ap.add_argument("--block", type=int, default=1024)
    args = ap.parse_args()

    t0 = time.time()
    tok = AutoTokenizer.from_pretrained(args.model)
    ids = tok(pathlib.Path(args.corpus).read_text(), return_tensors=None)["input_ids"]
    ds = Blocks(ids, args.block)
    log(f"corpus: {len(ids)} tokens -> {len(ds)} blocks of {args.block}")

    # 4-bit when bitsandbytes is present (needed for a 9B on a 16GB card);
    # plain bf16 otherwise — a 4B with frozen weights + LoRA-only grads fits.
    try:
        import bitsandbytes  # noqa: F401
        quant = BitsAndBytesConfig(load_in_4bit=True, bnb_4bit_quant_type="nf4",
                                   bnb_4bit_compute_dtype=torch.bfloat16,
                                   bnb_4bit_use_double_quant=True)
    except ImportError:
        quant = None
        log("bitsandbytes absent — training in bf16")

    model = load_wyrd_model(args.model, quantization_config=quant)
    model.config.use_cache = False

    lora = LoraConfig(r=args.rank, lora_alpha=args.rank * 2, lora_dropout=0.05,
                      task_type="CAUSAL_LM",
                      target_modules=["q_proj", "k_proj", "v_proj", "o_proj"])
    model = get_peft_model(model, lora)

    targs = TrainingArguments(
        output_dir=args.out, num_train_epochs=args.epochs,
        per_device_train_batch_size=1, gradient_accumulation_steps=8,
        learning_rate=1e-4, lr_scheduler_type="cosine", warmup_ratio=0.05,
        logging_steps=5, save_strategy="no", bf16=True,
        report_to=[], gradient_checkpointing=True)
    trainer = Trainer(model=model, args=targs, train_dataset=ds,
                      data_collator=DataCollatorForLanguageModeling(tok, mlm=False))
    result = trainer.train()

    model.save_pretrained(args.out)
    tok.save_pretrained(args.out)
    summary = {"spine_trained": True, "model": args.model,
               "epochs": args.epochs, "blocks": len(ds),
               "final_loss": result.training_loss,
               "adapter_dir": args.out,
               "train_minutes": round((time.time() - t0) / 60, 1)}
    (pathlib.Path(args.out) / "spine-summary.json").write_text(
        json.dumps(summary, indent=2))
    print(json.dumps(summary))


if __name__ == "__main__":
    main()
