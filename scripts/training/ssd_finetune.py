#!/usr/bin/env python3
"""
Step 3: SSD fine-tune — LoRA training with drive-prefix conditioning.

Takes the prepared corpus and fine-tunes the base model to understand drive prefixes.
After training, the model responds to [drives: grief=0.8 ...] by embodying grief,
without needing steering vectors at inference time.

Supports:
- Qwen3.5-4B (bf16 LoRA, ~10GB VRAM — recommended)
- Qwen2.5-7B-Instruct (QLoRA 4-bit, ~8GB VRAM — legacy)
- Any HuggingFace causal LM

Usage:
    source /tmp/steer-env/bin/activate
    python ssd_finetune.py --model Qwen/Qwen3.5-4B --data /tmp/ssd_corpus_v2_train.jsonl \
        --valid /tmp/ssd_corpus_v2_valid.jsonl --output /tmp/drive_adapter_3.5 --epochs 5
"""

import argparse
import json
import os

import torch
from transformers import AutoModelForCausalLM, AutoTokenizer, TrainingArguments
from torch.utils.data import Dataset


# LoRA target modules per architecture.
# DeltaNet (Qwen3.5): linear_attn projections + MLP
# Standard transformer (Qwen2.5, Qwen3): self_attn projections + MLP
LORA_TARGETS_QWEN35 = [
    # DeltaNet linear attention (24 layers)
    "in_proj_qkv", "in_proj_z", "in_proj_b", "in_proj_a", "out_proj",
    # Full attention (8 layers)
    "q_proj", "k_proj", "v_proj", "o_proj",
    # MLP (all 32 layers)
    "gate_proj", "up_proj", "down_proj",
]

LORA_TARGETS_STANDARD = [
    "q_proj", "k_proj", "v_proj", "o_proj",
    "gate_proj", "up_proj", "down_proj",
]


def role_per_token(tokenizer, rendered_text, max_length):
    """Return a list[role|None] aligned to the (unpadded, truncated) tokens of
    `rendered_text`. Uses the ChatML <|im_start|>{role} markers on the FULL
    render, so it sidesteps templates that reject partial message lists and
    tool_call content that escapes the {% generation %} block.

    Each <|im_start|>role ... block (up to the next <|im_start|> or end) is
    attributed wholly to that role; tokens before the first marker (rare
    template preamble) are None."""
    enc = tokenizer(rendered_text, truncation=True, max_length=max_length,
                    return_offsets_mapping=True, add_special_tokens=False)
    offsets = enc["offset_mapping"]
    marker = "<|im_start|>"
    # char ranges per role block
    blocks = []  # (start_char, end_char, role)
    i = rendered_text.find(marker)
    while i != -1:
        head = i + len(marker)
        nl = rendered_text.find("\n", head)
        role = rendered_text[head:nl].strip() if nl != -1 else ""
        nxt = rendered_text.find(marker, head)
        end = nxt if nxt != -1 else len(rendered_text)
        blocks.append((i, end, role))
        i = nxt
    role_of = [None] * len(offsets)
    for ti, (cs, ce) in enumerate(offsets):
        if cs == ce:  # special/empty offset
            continue
        for bs, be, role in blocks:
            if bs <= cs < be:
                role_of[ti] = role
                break
    return role_of


class ChatDataset(Dataset):
    """Load JSONL chat-format dataset.

    mask_mode controls which tokens carry cross-entropy gradient (#913):
      - "full"          : train on every non-pad token (legacy default; this is
                          the self-confirming baseline — the agent's own past
                          outputs are conditioning AND targets).
      - "assistant"     : standard instruct SFT — train only on assistant spans,
                          mask system/user (reproduce the policy).
      - "interventional": de Freitas & Ortega fix — mask the agent's OWN
                          (assistant) spans; train only on system/user/world
                          observations. Agent outputs stay in context as
                          conditioning but contribute no gradient. Breaks the
                          self-confirming loop on agent transcripts.
    """

    def __init__(self, path, tokenizer, max_length=2048, mask_mode="full"):
        self.examples = []
        self.tokenizer = tokenizer
        self.max_length = max_length
        self.mask_mode = mask_mode

        with open(path) as f:
            for line in f:
                data = json.loads(line)
                self.examples.append(data["messages"])

    def __len__(self):
        return len(self.examples)

    def __getitem__(self, idx):
        messages = self.examples[idx]

        # Format as chat template (full render — always template-valid)
        text = self.tokenizer.apply_chat_template(
            messages, tokenize=False, add_generation_prompt=False)

        tokens = self.tokenizer(
            text,
            truncation=True,
            max_length=self.max_length,
            padding="max_length",
            return_tensors="pt"
        )

        input_ids = tokens["input_ids"].squeeze()
        attention_mask = tokens["attention_mask"].squeeze()

        # Mask padding tokens in labels (-100 = ignored by cross-entropy loss)
        labels = input_ids.clone()
        labels[attention_mask == 0] = -100

        if self.mask_mode != "full":
            role_of = role_per_token(self.tokenizer, text, self.max_length)
            role_of = role_of + [None] * (input_ids.size(0) - len(role_of))
            for pos, role in enumerate(role_of):
                if role is None:
                    continue
                keep = (
                    (self.mask_mode == "assistant" and role == "assistant")
                    or (self.mask_mode == "interventional" and role != "assistant")
                )
                if not keep:
                    labels[pos] = -100
            labels[attention_mask == 0] = -100
        return {
            "input_ids": input_ids,
            "attention_mask": attention_mask,
            "labels": labels,
        }


def detect_architecture(model_name):
    """Detect if model is Qwen3.5 (hybrid DeltaNet) or standard transformer."""
    lower = model_name.lower()
    if "qwen3.5" in lower or "qwen3_5" in lower:
        return "qwen3.5"
    return "standard"


def main():
    parser = argparse.ArgumentParser(description="SSD LoRA fine-tune with drive prefixes")
    parser.add_argument("--model", default="Qwen/Qwen3.5-4B")
    parser.add_argument("--data", default="/tmp/ssd_corpus_v2_train.jsonl")
    parser.add_argument("--valid", default="/tmp/ssd_corpus_v2_valid.jsonl")
    parser.add_argument("--output", default="/tmp/drive_adapter_3.5")
    parser.add_argument("--epochs", type=int, default=5)
    parser.add_argument("--batch-size", type=int, default=1)
    parser.add_argument("--lr", type=float, default=2e-4)
    parser.add_argument("--lora-r", type=int, default=16)
    parser.add_argument("--lora-alpha", type=int, default=32)
    parser.add_argument("--max-length", type=int, default=512)
    parser.add_argument("--gradient-accumulation", type=int, default=8)
    parser.add_argument("--lora-dropout", type=float, default=0.05,
                        help="LoRA dropout. 0.05 default; bump to 0.10 for behavior-change "
                             "fine-tunes on small models prone to over-memorization.")
    parser.add_argument("--weight-decay", type=float, default=0.0,
                        help="AdamW weight decay. 0.0 default; 0.01 useful as anti-overfit lever.")
    parser.add_argument("--mask-mode", default="full",
                        choices=["full", "assistant", "interventional"],
                        help="Which token roles carry loss gradient. 'full' (legacy) "
                             "trains on all tokens; 'assistant' is standard instruct SFT "
                             "(policy); 'interventional' masks agent-written spans (#913).")
    parser.add_argument("--quantize", action="store_true",
                        help="Use 4-bit QLoRA (for larger models that don't fit in bf16)")
    parser.add_argument("--resume-from-checkpoint", default=None,
                        help="Resume from a checkpoint dir (e.g. /path/to/output/checkpoint-1191). "
                             "Can be 'true' to use the latest in --output. Restores LoRA weights, "
                             "optimizer state, scheduler, RNG, dataloader position. Use to swap "
                             "GPUs mid-run after an epoch boundary saves a checkpoint.")
    args = parser.parse_args()

    arch = detect_architecture(args.model)
    print(f"Model: {args.model} (architecture: {arch})")

    tokenizer = AutoTokenizer.from_pretrained(args.model, trust_remote_code=True)
    # Qwen3.5 has pad_token=<|endoftext|> (248044) and eos=<|im_end|> (248046)
    # Keep the native pad token — don't override with eos
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token

    # Load model — bf16 for Qwen3.5-4B (fits in 16GB), QLoRA for larger models
    if args.quantize:
        from transformers import BitsAndBytesConfig
        bnb_config = BitsAndBytesConfig(
            load_in_4bit=True,
            bnb_4bit_quant_type="nf4",
            bnb_4bit_compute_dtype=torch.bfloat16,
            bnb_4bit_use_double_quant=True,
        )
        model = AutoModelForCausalLM.from_pretrained(
            args.model,
            quantization_config=bnb_config,
            device_map={"": 0},  # Single visible GPU (set by CUDA_VISIBLE_DEVICES)
            trust_remote_code=True,
        )
        print("Loaded in 4-bit (QLoRA)")
    else:
        # Single GPU for LoRA training — device_map="auto" is for inference,
        # not training (PEFT needs model on one device).
        # 9B bf16 = ~18GB + LoRA optimizer ~1GB + activations ~10GB with grad checkpoint
        # = ~30GB total. Fits on 48GB A6000.
        model = AutoModelForCausalLM.from_pretrained(
            args.model,
            torch_dtype=torch.bfloat16,
            device_map={"": 0},  # Single visible GPU (set by CUDA_VISIBLE_DEVICES)
            trust_remote_code=True,
        )
        print(f"Loaded in bf16 on GPU 0 (VRAM: {torch.cuda.memory_allocated() / 1024**3:.1f} GB)")

    # Apply LoRA with architecture-appropriate targets
    from peft import LoraConfig, get_peft_model, TaskType

    target_modules = LORA_TARGETS_QWEN35 if arch == "qwen3.5" else LORA_TARGETS_STANDARD
    print(f"LoRA targets ({len(target_modules)}): {target_modules}")

    lora_config = LoraConfig(
        r=args.lora_r,
        lora_alpha=args.lora_alpha,
        target_modules=target_modules,
        lora_dropout=args.lora_dropout,
        bias="none",
        task_type=TaskType.CAUSAL_LM,
    )
    model = get_peft_model(model, lora_config)
    model.print_trainable_parameters()

    # Load datasets
    print(f"Loading training data: {args.data} (mask_mode={args.mask_mode})")
    train_dataset = ChatDataset(args.data, tokenizer, args.max_length, args.mask_mode)
    print(f"  {len(train_dataset)} training examples")

    valid_dataset = None
    if os.path.exists(args.valid):
        valid_dataset = ChatDataset(args.valid, tokenizer, args.max_length, args.mask_mode)
        print(f"  {len(valid_dataset)} validation examples")

    # Training arguments — bf16 for Qwen3.5, fp16 for older models
    use_bf16 = arch == "qwen3.5" and torch.cuda.is_bf16_supported()
    training_args = TrainingArguments(
        output_dir=args.output,
        num_train_epochs=args.epochs,
        per_device_train_batch_size=args.batch_size,
        gradient_accumulation_steps=args.gradient_accumulation,
        learning_rate=args.lr,
        weight_decay=args.weight_decay,
        lr_scheduler_type="cosine",
        warmup_ratio=0.1,
        max_grad_norm=1.0,  # gradient clipping to prevent NaN
        logging_steps=10,
        save_strategy="epoch",
        eval_strategy="no",  # Skip eval during training to avoid OOM (eval doesn't use grad checkpoint)
        bf16=use_bf16,
        fp16=not use_bf16,
        report_to="none",
        remove_unused_columns=False,
        dataloader_pin_memory=False,
        gradient_checkpointing=True,
        dataloader_num_workers=0,  # Qwen3.5 can hang with multiprocess dataloading
    )

    from transformers import Trainer, DataCollatorForLanguageModeling

    data_collator = DataCollatorForLanguageModeling(tokenizer=tokenizer, mlm=False)

    trainer = Trainer(
        model=model,
        args=training_args,
        train_dataset=train_dataset,
        eval_dataset=valid_dataset,
        data_collator=data_collator,
    )

    print(f"\nStarting training: {args.epochs} epochs, lr={args.lr}, "
          f"LoRA r={args.lora_r}, {'bf16' if use_bf16 else 'fp16'}")
    print(f"VRAM after setup: {torch.cuda.memory_allocated() / 1024**3:.1f} GB")

    # resume_from_checkpoint: None | True (= latest in output_dir) | str (explicit path).
    # HF Trainer accepts the bool-True case; we coerce the literal string "true" / "True".
    resume = args.resume_from_checkpoint
    if resume in ("true", "True"):
        resume = True
    if resume:
        print(f"Resuming from checkpoint: {resume}")

    trainer.train(resume_from_checkpoint=resume)

    # Save adapter
    model.save_pretrained(args.output)
    tokenizer.save_pretrained(args.output)
    print(f"\nAdapter saved to {args.output}")

    # Report size
    total_size = sum(
        os.path.getsize(os.path.join(args.output, f))
        for f in os.listdir(args.output)
        if os.path.isfile(os.path.join(args.output, f))
    )
    print(f"Adapter size: {total_size / 1024 / 1024:.1f} MB")


if __name__ == "__main__":
    main()
