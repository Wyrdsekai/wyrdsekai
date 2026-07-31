"""V7 AGENCY-RFT — GRPO trainer for the own-time act decision ( step 4).

Adapted from `scripts/training/emit_rft/run_emit_grpo.py` (which produced V6) for the WHOLE
agency battery. Same machinery — roll the policy out G times per own-time prompt, score each,
GRPO-update a LoRA on top of the warmed base — but with TWO changes vs emit:

  1. Reward = `reward_agency.score(completion, drive_value, match_tool)` (this dir), NOT the
     emit `reward.py`. It is a PURE function (the groundedness/verifier gate is the offline
     placeholder-arg proxy, #1 of the 2026-06-04 reward edits), so — unlike emit GRPO — there
     is **no RecipeValidateServer dependency**. The reward law: matching DO-tool (grounded) OR
     correct-rest = +1.0; wrong-act +0.2 expressive / +0.1 consequential (#3 cost-grade);
     confabulated/narrate/notice = 0.0; workaholic −0.5 expressive / −0.7 consequential.
  2. The bank columns it keys on are `drive_value` + `match_tool` (per rollout_bank.jsonl),
     not `generativity`.

Everything else mirrors emit GRPO: TRL GRPOTrainer over HF-`generate` rollouts (use_vllm=False —
this Qwen3.5+GatedDeltaNet model is only served by llama.cpp/mlx in prod, never vLLM); beta=0
(KL-free / DAPO-style → no frozen reference copy → one 9B ~18GB fits a single 48GB card);
gradient checkpointing; LoRA on the Qwen3.5 (DeltaNet) target modules.

Cold-start is the warmup-SFT (warmup_sft.py on warmup.jsonl, already merged → ~/wyrdsekai-9b-v7-warmed).
This script does GRPO only, on top of that warmed base.

    CUDA_VISIBLE_DEVICES=1 OMP_NUM_THREADS=1 python scripts/training/v7_agency/run_agency_grpo.py \
        --bank scripts/training/v7_agency/rollout_bank.jsonl \
        --base ~/wyrdsekai-9b-v7-warmed \
        --out  ~/wyrdsekai-9b-v7-agency-grpo \
        --steps 300 --num-generations 8 --lr 1e-6 --lora-r 16 --lora-alpha 32

Select the checkpoint with the best AgencyBattery enact-rate (act-when-warranted AND
restraint-when-not — both), then merge + GGUF + deploy + gate (RUN.md steps 5-6).
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import reward_agency as RA

# Qwen3.5 (DeltaNet) LoRA targets — mirror emit GRPO / ssd_finetune.py so the adapter lands
# on the same modules the substrate + warmup SFT trained.
QWEN35_TARGETS = [
    "in_proj_qkv", "in_proj_z", "in_proj_b", "in_proj_a", "out_proj",
    "q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj",
]


def load_dataset(bank_path: str, tok):
    """Pre-RENDER each prompt to a string WITH the tool schemas baked in (add_generation_prompt).

    Critical: passing `prompt` as a raw message list + `tools` as a separate column does NOT put
    the tools in the rollout context — TRL forwards unknown columns to the reward fn, not to the
    chat template. That dropped the tool schemas from the GRPO rollout (but NOT from warmup, which
    applied them), so the warmed model saw a different prompt at RL time, collapsed to deterministic
    narration/rest per prompt -> within-group reward std 0 -> frac_reward_zero_std~1.0 -> loss 0.
    Rendering here matches warmup_sft.py AND probe_warmed_diversity.py exactly (no train/serve skew)."""
    from datasets import Dataset
    rows = [json.loads(l) for l in Path(bank_path).read_text().splitlines() if l.strip()]
    def render(r):
        return tok.apply_chat_template(r["messages"], tools=r.get("tools"),
                                       add_generation_prompt=True, tokenize=False)
    data = {
        "prompt": [render(r) for r in rows],              # standard (string) prompt — tools already in it
        "drive_value": [float(r.get("drive_value", 0.0)) for r in rows],
        "match_tool": [str(r.get("match_tool", "")) for r in rows],
    }
    return Dataset.from_dict(data)


def _completion_text(c) -> str:
    # TRL conversational completions are [{'role':'assistant','content':...}]; standard are str.
    if isinstance(c, str):
        return c
    if isinstance(c, list) and c and isinstance(c[-1], dict):
        return c[-1].get("content", "") or ""
    if isinstance(c, dict):
        return c.get("content", "") or ""
    return str(c)


def make_reward_fn():
    """TRL reward_func signature: (prompts, completions, **cols) -> list[float].
    `drive_value` + `match_tool` columns are forwarded by TRL as per-row kwarg lists."""
    def agency_reward(prompts, completions, **cols):
        dvs = cols.get("drive_value") or [0.0] * len(completions)
        mts = cols.get("match_tool") or [""] * len(completions)
        out = []
        for c, dv, mt in zip(completions, dvs, mts):
            try:
                out.append(RA.score(_completion_text(c), float(dv), str(mt)))
            except Exception as e:  # a reward never kills a long run — score 0, keep going
                print(f"[grpo] reward error (scored 0.0): {e}", file=sys.stderr)
                out.append(0.0)
        return out
    agency_reward.__name__ = "agency_reward"
    return agency_reward


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--bank", default="scripts/training/v7_agency/rollout_bank.jsonl")
    ap.add_argument("--base", required=True, help="HF dir of the warmed 9B (~/wyrdsekai-9b-v7-warmed)")
    ap.add_argument("--out", required=True)
    ap.add_argument("--steps", type=int, default=300)
    ap.add_argument("--num-generations", type=int, default=8)
    ap.add_argument("--lr", type=float, default=1e-6)
    ap.add_argument("--lora-r", type=int, default=16)
    ap.add_argument("--lora-alpha", type=int, default=32)
    ap.add_argument("--max-completion-len", type=int, default=512)
    ap.add_argument("--batch-size", type=int, default=8)
    ap.add_argument("--grad-accum", type=int, default=4)
    ap.add_argument("--temperature", type=float, default=1.2,
                    help="rollout sampling temp. 0.9 collapsed within-group reward variance "
                         "(frac_reward_zero_std=1.0 -> loss 0); the diversity probe showed 1.2-1.3 "
                         "restores multi-valued reward groups. See probe_warmed_diversity.py.")
    ap.add_argument("--top-p", type=float, default=1.0, help="keep 1.0 so TRL doesn't re-truncate rollout diversity")
    ap.add_argument("--top-k", type=int, default=0, help="0 = disabled; do NOT top-k truncate (kills within-group spread)")
    ap.add_argument("--logging-steps", type=int, default=10)
    ap.add_argument("--save-steps", type=int, default=0, help="0 = steps//4")
    ap.add_argument("--emit-json", action="store_true")
    ap.add_argument("--shard", action="store_true",
                    help="naive model-parallel (device_map=auto) across visible GPUs — NOT needed "
                         "for a 9B with beta=0 (~18GB fits one 48GB card); use only if OOM")
    args = ap.parse_args()

    # Self-check the reward before a long run (no server needed — RA is pure).
    import subprocess
    rc = subprocess.run([sys.executable, os.path.join(os.path.dirname(__file__), "reward_agency.py")])
    if rc.returncode != 0:
        print("[grpo] reward_agency self-check FAILED — aborting", file=sys.stderr)
        return 2

    import torch
    from transformers import AutoTokenizer, AutoModelForCausalLM
    from peft import LoraConfig
    from trl import GRPOConfig, GRPOTrainer

    tok = AutoTokenizer.from_pretrained(args.base, trust_remote_code=True)
    ds = load_dataset(args.bank, tok)
    print(f"[grpo] dataset={len(ds)} prompts; base={args.base}")
    print(f"[grpo] sample rendered prompt[0] (last 320 chars):\n...{ds[0]['prompt'][-320:]}")

    # bf16 explicit (a path-string load defaults to fp32 → ~36GB → OOM). beta=0 → no ref copy.
    if args.shard:
        print("[grpo] sharding base across visible GPUs (device_map=auto)")
        base_model = AutoModelForCausalLM.from_pretrained(
            args.base, dtype=torch.bfloat16, device_map="auto", trust_remote_code=True)
    else:
        print("[grpo] loading base in bf16 on a single GPU")
        base_model = AutoModelForCausalLM.from_pretrained(
            args.base, dtype=torch.bfloat16, trust_remote_code=True).to("cuda")
    base_model.config.use_cache = False
    # Force the rollout to SAMPLE (not greedy) and not re-truncate diversity. A base
    # generation_config with do_sample=False or a tight top_k/top_p collapses the G rollouts
    # to near-identical text -> within-group reward std 0 -> frac_reward_zero_std=1.0 -> loss 0
    # (the exact failure of the 2026-06-04 first run; diagnosed via probe_warmed_diversity.py).
    if base_model.generation_config is not None:
        base_model.generation_config.do_sample = True
        base_model.generation_config.temperature = args.temperature
        base_model.generation_config.top_p = args.top_p
        base_model.generation_config.top_k = args.top_k

    peft_cfg = LoraConfig(
        r=args.lora_r, lora_alpha=args.lora_alpha, lora_dropout=0.05,
        bias="none", task_type="CAUSAL_LM", target_modules=QWEN35_TARGETS,
    )
    cfg = GRPOConfig(
        output_dir=args.out,
        per_device_train_batch_size=args.batch_size,
        gradient_accumulation_steps=args.grad_accum,
        num_generations=args.num_generations,
        max_completion_length=args.max_completion_len,
        learning_rate=args.lr,
        max_steps=args.steps,
        bf16=torch.cuda.is_available(),
        logging_steps=args.logging_steps,
        save_steps=args.save_steps if args.save_steps > 0 else max(50, args.steps // 4),
        temperature=args.temperature,   # exploration during rollout (1.2 — 0.9 collapsed within-group std)
        top_p=args.top_p,               # 1.0 — do not narrow the rollout distribution
        top_k=args.top_k,               # 0 — disable top-k truncation (it killed within-group reward variance)
        use_vllm=False,                 # HF generate — one engine; Qwen3.5+DeltaNet isn't vLLM-served
        beta=0.0,                       # KL-free (DAPO) → no frozen ref → one 9B fits a single 48GB card
        report_to=[],
        gradient_checkpointing=True,
        gradient_checkpointing_kwargs={"use_reentrant": False},
    )
    trainer = GRPOTrainer(
        model=base_model,
        reward_funcs=[make_reward_fn()],
        args=cfg,
        train_dataset=ds,
        peft_config=peft_cfg,
        processing_class=tok,
    )
    trainer.train()
    trainer.save_model(args.out)
    print(f"[grpo] saved adapter → {args.out}")
    if args.emit_json:
        print(json.dumps({"ok": True, "out": args.out, "prompts": len(ds),
                          "steps": args.steps, "num_generations": args.num_generations}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
