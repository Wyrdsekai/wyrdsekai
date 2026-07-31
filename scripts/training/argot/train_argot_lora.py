#!/usr/bin/env python3
"""train the Tier-B argot LoRA adapter on the 4B voice model.

Layer A (ZoneArgotService / ArgotCodec) is the deterministic codebook the runtime ENCODES and
DECODES with; this adapter is Layer B — it makes the 4B *fluent* in the current codebook so the
mapping is baked sub-symbolically (non-extractable from observed traffic) rather than only applied
as a post-hoc string substitution. The corpus (generate_argot_corpus.py) pairs a natural-language
coordination line with its argot rendering under the zone's SECRET seed, so an adapter trained here
speaks exactly the argot the Java runtime decodes with once the zone-secret provider is installed.

This is a focused sibling of ssd_finetune.py with two argot-specific fixes:
  1. Architecture is KNOWN to be Qwen3.5 (hybrid DeltaNet) — the merged-model PATH says nothing,
     so ssd_finetune's path-string detection would mis-pick standard-transformer LoRA targets.
     We hardcode the DeltaNet targets.
  2. The chat template lives in a separate chat_template.jinja (not tokenizer_config.json). Recent
     transformers auto-load it, but we set it explicitly so the render is deterministic regardless.

Default mask_mode is "assistant" — standard instruct SFT: learn to PRODUCE the argot, condition on
(but don't train on) the system/user framing.

Usage:
  source /tmp/steer-env/bin/activate   # or whatever venv has torch+peft
  python train_argot_lora.py \
      --base ~/models/wyrdsekai-4b-v10-merged \
      --data /tmp/argot_corpus_home-server.jsonl \
      --output /tmp/argot_adapter_4b \
      --epochs 4
"""
import argparse
import json
import os

import torch
import transformers
from transformers import AutoModelForCausalLM, AutoTokenizer, TrainingArguments
from torch.utils.data import Dataset


def load_base_model(base):
    """Load the merged v10 4B as a text-only causal LM, working around a packaging quirk.

    The shipped config.json was hand-set to architectures=[Qwen3_5ForConditionalGeneration] (for the
    GGUF/MLX path), but that's the MULTIMODAL class — transformers 5.x builds it from DEFAULT
    sub-configs (hidden 4096 + a vision tower), ignoring the flat hidden_size=2560 and mismatching
    every real weight (→ reinit → OOM). The weights themselves are a plain text-only Qwen3.5-4B
    (hidden 2560) stored under the conditional-generation prefix `model.language_model.*`.

    So: rebuild the model from the CORRECT text config (config.json.original = Qwen3_5ForCausalLM /
    qwen3_5_text), then load the safetensors with the prefix stripped to `model.*`. lm_head is tied
    (omitted from the checkpoint) → tie_weights() restores it. Verified: 426/426 tensors map, only
    the tied lm_head is "missing", zero unexpected.
    """
    from safetensors.torch import load_file
    from transformers import AutoConfig
    orig = os.path.join(base, "config.json.original")
    cfg_path = orig if os.path.exists(orig) else os.path.join(base, "config.json")
    d = json.load(open(cfg_path))
    mt = d.pop("model_type"); d.pop("architectures", None); d.pop("transformers_version", None)
    cfg = AutoConfig.for_model(mt, **d)
    print(f"Config: {cfg.model_type} hidden={cfg.hidden_size} layers={cfg.num_hidden_layers} "
          f"tie_word_embeddings={cfg.tie_word_embeddings}")
    model = AutoModelForCausalLM.from_config(cfg, dtype=torch.bfloat16, trust_remote_code=True)
    sd = load_file(os.path.join(base, "model.safetensors"))
    remap = {(k.replace("model.language_model.", "model.")
              if k.startswith("model.language_model.") else k): v for k, v in sd.items()}
    missing, unexpected = model.load_state_dict(remap, strict=False)
    model.tie_weights()
    non_tied_missing = [m for m in missing if "lm_head" not in m]
    print(f"load_state_dict: {len(missing)} missing ({len(non_tied_missing)} non-lm_head), "
          f"{len(unexpected)} unexpected")
    if non_tied_missing or unexpected:
        raise RuntimeError(f"weight load mismatch: missing={non_tied_missing[:6]} "
                           f"unexpected={unexpected[:6]}")
    return model.to("cuda")

# Qwen3.5 hybrid DeltaNet LoRA targets (linear-attn projections + full-attn projections + MLP),
# byte-identical to ssd_finetune.LORA_TARGETS_QWEN35.
LORA_TARGETS_QWEN35 = [
    "in_proj_qkv", "in_proj_z", "in_proj_b", "in_proj_a", "out_proj",
    "q_proj", "k_proj", "v_proj", "o_proj",
    "gate_proj", "up_proj", "down_proj",
]

# MLP-only target set. The full set above can't be served on llama-server: convert_lora_to_gguf
# hits NotImplementedError on the Qwen3.5 attention v-head reorder (same DeltaNet/GQA GGUF blocker
# as the Layer-C MLX-fuse issue). The MLP projections have NO head reorder, so an MLP-only LoRA
# IS GGUF-convertible — and serves identically on llama-server (GGUF) and MLX (PEFT dir). One
# adapter, both prod backends. The bet: 32 layers of gate/up/down_proj still learn the codebook.
LORA_TARGETS_MLP_ONLY = ["gate_proj", "up_proj", "down_proj"]

IM_START = "<|im_start|>"


def assistant_token_mask(tokenizer, rendered_text, max_length):
    """Return list[bool] aligned to the truncated tokens of rendered_text — True where the token
    belongs to an <|im_start|>assistant block (the argot we want gradient on). Mirrors the marker
    walk in ssd_finetune.role_per_token but specialized to the assistant role."""
    enc = tokenizer(rendered_text, truncation=True, max_length=max_length,
                    return_offsets_mapping=True, add_special_tokens=False)
    offsets = enc["offset_mapping"]
    blocks = []  # (start_char, end_char, is_assistant)
    i = rendered_text.find(IM_START)
    while i != -1:
        head = i + len(IM_START)
        nl = rendered_text.find("\n", head)
        role = rendered_text[head:nl].strip() if nl != -1 else ""
        nxt = rendered_text.find(IM_START, head)
        end = nxt if nxt != -1 else len(rendered_text)
        blocks.append((i, end, role == "assistant"))
        i = nxt
    keep = [False] * len(offsets)
    for ti, (cs, ce) in enumerate(offsets):
        if cs == ce:
            continue
        for bs, be, is_asst in blocks:
            if bs <= cs < be:
                keep[ti] = is_asst
                break
    return keep


class ArgotDataset(Dataset):
    def __init__(self, path, tokenizer, max_length, mask_mode):
        self.examples = [json.loads(l)["messages"] for l in open(path) if l.strip()]
        self.tokenizer = tokenizer
        self.max_length = max_length
        self.mask_mode = mask_mode

    def __len__(self):
        return len(self.examples)

    def __getitem__(self, idx):
        messages = self.examples[idx]
        # The v10 4B is a REASONING model: at generation its template appends `<think>\n` unless
        # enable_thinking=False (which instead emits an empty `<think>\n\n</think>\n\n`). Our argot is
        # a direct transduction — no reasoning. So we bake the EMPTY think block into the assistant
        # target here, exactly matching what enable_thinking=False produces at inference. Without this,
        # training teaches bare `<|im_start|>assistant\n§argot` while generation starts from
        # `<|im_start|>assistant\n<think>\n` → the model reasons instead of emitting argot.
        messages = [dict(m) for m in messages]
        for m in messages:
            if m["role"] == "assistant" and "</think>" not in m["content"]:
                m["content"] = "<think>\n\n</think>\n\n" + m["content"]
        text = self.tokenizer.apply_chat_template(
            messages, tokenize=False, add_generation_prompt=False)
        tokens = self.tokenizer(text, truncation=True, max_length=self.max_length,
                                padding="max_length", return_tensors="pt")
        input_ids = tokens["input_ids"].squeeze()
        attention_mask = tokens["attention_mask"].squeeze()
        labels = input_ids.clone()
        labels[attention_mask == 0] = -100
        if self.mask_mode == "assistant":
            keep = assistant_token_mask(self.tokenizer, text, self.max_length)
            keep = keep + [False] * (input_ids.size(0) - len(keep))
            for pos, k in enumerate(keep):
                if not k:
                    labels[pos] = -100
            labels[attention_mask == 0] = -100
        return {"input_ids": input_ids, "attention_mask": attention_mask, "labels": labels}


def main():
    ap = argparse.ArgumentParser(description="Train the Tier-B argot LoRA adapter")
    ap.add_argument("--base", default=os.path.expanduser("~/models/wyrdsekai-4b-v10-merged"))
    ap.add_argument("--data", default="/tmp/argot_corpus_home-server.jsonl")
    ap.add_argument("--output", default="/tmp/argot_adapter_4b")
    ap.add_argument("--epochs", type=int, default=4)
    ap.add_argument("--batch-size", type=int, default=1)
    ap.add_argument("--lr", type=float, default=2e-4)
    ap.add_argument("--lora-r", type=int, default=16)
    ap.add_argument("--lora-alpha", type=int, default=32)
    ap.add_argument("--lora-dropout", type=float, default=0.05)
    ap.add_argument("--max-length", type=int, default=384)
    ap.add_argument("--gradient-accumulation", type=int, default=8)
    ap.add_argument("--mask-mode", default="assistant", choices=["assistant", "full"])
    ap.add_argument("--mlp-only", action="store_true",
                    help="restrict LoRA to MLP projections (gate/up/down_proj) so the adapter is "
                         "GGUF-convertible + servable on llama-server (attention LoRA can't convert "
                         "for the Qwen3.5 DeltaNet/GQA arch)")
    args = ap.parse_args()
    targets = LORA_TARGETS_MLP_ONLY if args.mlp_only else LORA_TARGETS_QWEN35

    base = os.path.expanduser(args.base)
    print(f"Base: {base}")
    tokenizer = AutoTokenizer.from_pretrained(base, trust_remote_code=True)
    # Set the chat template explicitly (it lives in chat_template.jinja, not tokenizer_config.json).
    tpl_path = os.path.join(base, "chat_template.jinja")
    if os.path.exists(tpl_path):
        tokenizer.chat_template = open(tpl_path).read()
        print("chat_template.jinja loaded explicitly")
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token

    model = load_base_model(base)
    print(f"Loaded bf16 on GPU 0 (VRAM: {torch.cuda.memory_allocated()/1024**3:.1f} GB)")

    from peft import LoraConfig, get_peft_model, TaskType
    print(f"LoRA targets ({len(targets)}, mlp_only={args.mlp_only}): {targets}")
    model = get_peft_model(model, LoraConfig(
        r=args.lora_r, lora_alpha=args.lora_alpha, target_modules=targets,
        lora_dropout=args.lora_dropout, bias="none", task_type=TaskType.CAUSAL_LM))
    model.print_trainable_parameters()
    # Required for gradient checkpointing + LoRA (frozen base): make the input embeddings emit
    # grad-tracked activations so checkpointed blocks have something to backprop through.
    model.enable_input_require_grads()

    ds = ArgotDataset(args.data, tokenizer, args.max_length, args.mask_mode)
    print(f"{len(ds)} argot SFT pairs (mask_mode={args.mask_mode})")

    use_bf16 = torch.cuda.is_bf16_supported()
    training_args = TrainingArguments(
        output_dir=args.output,
        num_train_epochs=args.epochs,
        per_device_train_batch_size=args.batch_size,
        gradient_accumulation_steps=args.gradient_accumulation,
        learning_rate=args.lr,
        lr_scheduler_type="cosine",
        warmup_ratio=0.1,
        max_grad_norm=1.0,
        logging_steps=10,
        save_strategy="epoch",
        eval_strategy="no",
        bf16=use_bf16,
        fp16=not use_bf16,
        report_to="none",
        remove_unused_columns=False,
        dataloader_pin_memory=False,
        gradient_checkpointing=True,
        dataloader_num_workers=0,
    )
    # default_data_collator just stacks our pre-built input_ids/attention_mask/labels tensors —
    # it does NOT re-derive labels from input_ids (which DataCollatorForLanguageModeling would,
    # clobbering the assistant-span masking we computed in ArgotDataset).
    from transformers import Trainer, default_data_collator
    trainer = Trainer(model=model, args=training_args, train_dataset=ds,
                      data_collator=default_data_collator)

    print(f"\nTraining: {args.epochs} epochs, lr={args.lr}, r={args.lora_r}, "
          f"{'bf16' if use_bf16 else 'fp16'}, eff_batch={args.batch_size*args.gradient_accumulation}")
    trainer.train()

    model.save_pretrained(args.output)
    tokenizer.save_pretrained(args.output)
    print(f"\nAdapter saved to {args.output}")


if __name__ == "__main__":
    main()
