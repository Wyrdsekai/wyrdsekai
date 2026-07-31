"""/ P4-warmup — teacher-gold cold-start SFT.

The pre-GRPO diagnostic proved the base emits shape_recipe 0% on the captured own-time
prompts (it narrates), so every high-band rollout earns the same reward -> zero GRPO
advantage -> no gradient. GRPO cannot bootstrap from all-failure. This light SFT on the
teacher-gold corpus (warmup.jsonl: 96 valid EMIT + 96 REST) seeds *some* emission so the
subsequent GRPO has within-prompt reward variance to optimise — the MENTOR-style step.

Zero train/serve skew: the chat template is applied WITH the captured `tools`, exactly as
the live serving path (and run_emit_grpo) presents them. Trains a LoRA on the same Qwen3.5
DeltaNet targets as GRPO, then merges -> a warmed HF base for `run_emit_grpo --base`.

    python warmup_sft.py --corpus data/training/emit_rft/warmup.jsonl \
        --base ~/wyrdsekai-9b-v4-merged --out ~/wyrdsekai-9b-emit-warmed \
        --epochs 2 --lr 1e-5 --lora-r 16 --lora-alpha 32
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

# same DeltaNet LoRA targets as run_emit_grpo / ssd_finetune
QWEN35_TARGETS = [
    "in_proj_qkv", "in_proj_z", "in_proj_b", "in_proj_a", "out_proj",
    "q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj",
]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--corpus", required=True)
    ap.add_argument("--base", required=True)
    ap.add_argument("--out", required=True, help="output dir for the MERGED warmed model")
    ap.add_argument("--epochs", type=float, default=2.0)
    ap.add_argument("--lr", type=float, default=1e-5)
    ap.add_argument("--lora-r", type=int, default=16)
    ap.add_argument("--lora-alpha", type=int, default=32)
    ap.add_argument("--max-length", type=int, default=4096)
    ap.add_argument("--batch-size", type=int, default=1)
    ap.add_argument("--grad-accum", type=int, default=8)
    ap.add_argument("--adapter-out", default="", help="optional: also keep the raw adapter dir")
    args = ap.parse_args()

    import torch
    from datasets import Dataset
    from transformers import (AutoModelForCausalLM, AutoTokenizer, Trainer,
                              TrainingArguments, DataCollatorForSeq2Seq)
    from peft import LoraConfig, get_peft_model

    tok = AutoTokenizer.from_pretrained(args.base, trust_remote_code=True)

    rows = [json.loads(l) for l in Path(args.corpus).read_text().splitlines() if l.strip()]
    # Completion-only masking, done EXPLICITLY (no trl-version-dependent collator):
    # tokenize the full conversation (prompt + gold turn) and the prompt alone (with the
    # assistant header), then mask the prompt span to -100 so the gradient lands only on
    # the gold turn (valid recipe YAML / rest reflection). Tools threaded both times so the
    # warmed model sees exactly the serving distribution. Full-seq loss drowned the emit.
    feats, n_emit, n_rest, n_skip = [], 0, 0, 0
    for r in rows:
        msgs = r["messages"]
        tools = r.get("tools")
        full = tok.apply_chat_template(msgs, tools=tools, tokenize=False, add_generation_prompt=False)
        # prefix = everything BEFORE the gold assistant turn (no generation prompt, so it is
        # a clean token-prefix of `full`); its length is the mask boundary.
        prefix = tok.apply_chat_template(msgs[:-1], tools=tools, tokenize=False, add_generation_prompt=False)
        full_ids = tok(full, truncation=True, max_length=args.max_length)["input_ids"]
        plen = len(tok(prefix, truncation=True, max_length=args.max_length)["input_ids"])
        if plen >= len(full_ids):  # gold turn fully truncated away — skip
            n_skip += 1
            continue
        labels = [-100] * plen + full_ids[plen:]
        feats.append({"input_ids": full_ids, "labels": labels, "attention_mask": [1] * len(full_ids)})
        if r.get("label") == "emit":
            n_emit += 1
        else:
            n_rest += 1
    ds = Dataset.from_list(feats)
    print(f"[warmup] corpus={len(rows)} emit={n_emit} rest={n_rest} skipped={n_skip} "
          f"(completion-only masking) base={args.base}")

    peft_cfg = LoraConfig(
        r=args.lora_r, lora_alpha=args.lora_alpha, lora_dropout=0.05,
        bias="none", task_type="CAUSAL_LM", target_modules=QWEN35_TARGETS,
    )
    adapter_dir = args.adapter_out or (args.out.rstrip("/") + "-adapter")
    model = AutoModelForCausalLM.from_pretrained(
        args.base, trust_remote_code=True, torch_dtype=torch.bfloat16, device_map="cuda")
    model = get_peft_model(model, peft_cfg)
    model.print_trainable_parameters()
    # Long (4k) own-time prompts on a 9B OOM a 48GB card with full activations — gradient
    # checkpointing trades compute for memory. enable_input_require_grads is required for
    # checkpointing to flow grads into the LoRA adapters.
    model.config.use_cache = False
    model.gradient_checkpointing_enable(gradient_checkpointing_kwargs={"use_reentrant": False})
    model.enable_input_require_grads()
    targs = TrainingArguments(
        output_dir=adapter_dir,
        num_train_epochs=args.epochs,
        per_device_train_batch_size=args.batch_size,
        gradient_accumulation_steps=args.grad_accum,
        learning_rate=args.lr,
        bf16=torch.cuda.is_available(),
        logging_steps=10,
        report_to=[],
        save_strategy="no",
        remove_unused_columns=False,
        gradient_checkpointing=True,
        gradient_checkpointing_kwargs={"use_reentrant": False},
    )
    trainer = Trainer(
        model=model, args=targs, train_dataset=ds,
        data_collator=DataCollatorForSeq2Seq(tok, padding=True, label_pad_token_id=-100),
    )
    trainer.train()
    model.save_pretrained(adapter_dir)
    print(f"[warmup] adapter saved -> {adapter_dir}")
    del model, trainer
    torch.cuda.empty_cache()

    # Merge LoRA into the base so GRPO can point --base at a clean HF dir.
    print("[warmup] merging adapter into base …")
    from peft import PeftModel
    base = AutoModelForCausalLM.from_pretrained(
        args.base, trust_remote_code=True, torch_dtype=torch.bfloat16, device_map="cpu")
    merged = PeftModel.from_pretrained(base, adapter_dir)
    merged = merged.merge_and_unload()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    merged.save_pretrained(str(out))
    tok.save_pretrained(str(out))
    print(f"[warmup] MERGED warmed base -> {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
