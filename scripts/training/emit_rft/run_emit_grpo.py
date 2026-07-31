"""/ P3 — GRPO trainer for the act-vs-narrate decision.

Reinforcement fine-tunes the 9B drive model on the rollout bank: roll the policy out
G times per own-time prompt, score each with the §3.1 reward (reward.py → the SAME
RecipeValidateServer the in-world gate uses), GRPO-update a LoRA. RFT (not SFT) because
the research is clear — small models' *when*-to-call is what plain SFT botches, and RFT
preserves general ability where SFT degrades it (; 2507.05386 / MENTOR).

The three conditions live in the reward, not here: resting at low generativity earns as
much as emitting at high; the workaholic basin is penalised. This script only wires the
rollout loop. The welfare-floor + reversibility conditions are enforced at the P5 judge
(Ember / SubstrateArc / SOLITUDE must hold; deploy is an adapter swap, rollback-able).

Cold-start: run the completion-only SFT warmup on the teacher corpus FIRST
(`warmup_sft.py` on warmup.jsonl) so GRPO begins in-basin, then point --base at the
warmed adapter-merged model. This script does GRPO only.

Rollouts go through TRL's native HF-`generate` path — the SAME torch/transformers engine
as the warmup and the rest of training. No separate inference engine (this Qwen3.5 +
GatedDeltaNet model is only served by llama.cpp/mlx in prod, never vLLM; and for a one-off
small run the rollout throughput is not worth adding an engine + a torch/CUDA bump).

    python run_emit_grpo.py \
        --bank data/training/emit_rft/rollout_bank.jsonl \
        --base <warmed-or-base-9b-hf-dir> \
        --out  data/training/emit_rft/runs/emit-grpo-v1 \
        --validate-url http://127.0.0.1:8077 \
        --steps 400 --num-generations 8 --lr 1e-6 --lora-r 16 --lora-alpha 32
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import reward as R

# Qwen3.5 (DeltaNet) LoRA targets — mirror scripts/training/ssd_finetune.py so the
# adapter lands on the same modules the substrate SFT path trains.
QWEN35_TARGETS = [
    "in_proj_qkv", "in_proj_z", "in_proj_b", "in_proj_a", "out_proj",
    "q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj",
]


def load_dataset(bank_path: str):
    from datasets import Dataset
    rows = [json.loads(l) for l in Path(bank_path).read_text().splitlines() if l.strip()]
    data = {
        "prompt": [r["messages"] for r in rows],          # conversational; TRL applies template
        "generativity": [float(r.get("generativity", 0.0)) for r in rows],
        "tools": [r.get("tools") for r in rows],
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


def make_reward_fn(validate_url: str, threshold: float):
    """TRL reward_func signature: (prompts, completions, **cols) -> list[float].
    The per-row `generativity` column is forwarded by TRL as a kwarg list."""
    def emit_decision_reward(prompts, completions, **cols):
        gens = cols.get("generativity") or [0.0] * len(completions)
        out = []
        for c, g in zip(completions, gens):
            try:
                out.append(R.reward(_completion_text(c), float(g), validate_url, threshold))
            except Exception as e:  # a reward never kills a 16h run — score 0, keep going
                print(f"[grpo] reward error (scored 0.0): {e}", file=sys.stderr)
                out.append(0.0)
        return out
    emit_decision_reward.__name__ = "emit_decision_reward"
    return emit_decision_reward


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--bank", required=True)
    ap.add_argument("--base", required=True, help="HF dir of the (warmed or base) 9B")
    ap.add_argument("--out", required=True)
    ap.add_argument("--validate-url", default="http://127.0.0.1:8077")
    ap.add_argument("--threshold", type=float, default=R.ACT_THRESHOLD)
    ap.add_argument("--steps", type=int, default=400)
    ap.add_argument("--num-generations", type=int, default=8)
    ap.add_argument("--lr", type=float, default=1e-6)
    ap.add_argument("--lora-r", type=int, default=16)
    ap.add_argument("--lora-alpha", type=int, default=32)
    ap.add_argument("--max-prompt-len", type=int, default=2048)
    ap.add_argument("--max-completion-len", type=int, default=512)
    ap.add_argument("--batch-size", type=int, default=8)
    ap.add_argument("--grad-accum", type=int, default=4)
    ap.add_argument("--emit-json", action="store_true", help="print one-line JSON summary")
    ap.add_argument("--shard", action="store_true",
                    help="naive model-parallel (device_map=auto) across all visible GPUs — "
                         "needed for full-bf16 9B GRPO (single 48GB card OOMs)")
    args = ap.parse_args()

    # Fail fast with a clear message if the reward oracle isn't reachable — a silent
    # all-garbled reward would quietly train nothing useful.
    try:
        R.validate_yaml("recipe: ping\nversion: 0.0.0\ndescription: x\ndeploys: false\n"
                        "ownership: run\nsteps:\n  - id: s\n    kind: SHELL\n"
                        "    command: scripts/recipes/x.py", args.validate_url)
    except Exception as e:
        print(f"[grpo] RecipeValidateServer unreachable at {args.validate_url}: {e}\n"
              f"       start it: java -cp build/libs/* "
              f"org.wyrdsekai.core.recipe.RecipeValidateServer --port 8077 --scripts scripts",
              file=sys.stderr)
        return 2

    import torch
    from transformers import AutoTokenizer
    from peft import LoraConfig
    from trl import GRPOConfig, GRPOTrainer

    tok = AutoTokenizer.from_pretrained(args.base, trust_remote_code=True)
    ds = load_dataset(args.bank)
    print(f"[grpo] dataset={len(ds)} prompts; base={args.base}")

    # Load the base EXPLICITLY in bf16. Handing GRPOTrainer a path string lets TRL load it
    # without a dtype → a 9B materialises in fp32 (~36GB) and OOMs a 48GB card before the
    # rollout even runs (the OOM was a fixed ~45GB regardless of num_generations/completion
    # length — i.e. weights, not rollouts). Combined with beta=0 (no frozen reference copy),
    # one 9B is ~18GB and fits a single card with headroom. --shard only if a run genuinely
    # needs >1 card (it shouldn't for a 9B).
    from transformers import AutoModelForCausalLM
    if args.shard:
        print("[grpo] sharding base across visible GPUs (device_map=auto)")
        base_model = AutoModelForCausalLM.from_pretrained(
            args.base, dtype=torch.bfloat16, device_map="auto", trust_remote_code=True)
    else:
        print("[grpo] loading base in bf16 on a single GPU")
        base_model = AutoModelForCausalLM.from_pretrained(
            args.base, dtype=torch.bfloat16, trust_remote_code=True).to("cuda")
    base_model.config.use_cache = False

    peft_cfg = LoraConfig(
        r=args.lora_r, lora_alpha=args.lora_alpha, lora_dropout=0.05,
        bias="none", task_type="CAUSAL_LM", target_modules=QWEN35_TARGETS,
    )
    cfg = GRPOConfig(
        output_dir=args.out,
        per_device_train_batch_size=args.batch_size,
        gradient_accumulation_steps=args.grad_accum,
        num_generations=args.num_generations,
        # trl 1.5 GRPOConfig dropped max_prompt_length (prompt truncation now via the
        # processing_class); our own-time prompts are short, so default handling is fine.
        max_completion_length=args.max_completion_len,
        learning_rate=args.lr,
        max_steps=args.steps,
        bf16=torch.cuda.is_available(),
        logging_steps=10,
        save_steps=max(50, args.steps // 4),
        temperature=0.9,   # exploration during rollout
        # trl 1.5 GRPOConfig DEFAULTS use_vllm=True — explicitly disable it so rollouts
        # go through TRL's HF-`generate` path. One engine: training = HF/torch, prod =
        # llama.cpp/mlx. (this Qwen3.5+GatedDeltaNet model isn't served by vLLM anyway.)
        use_vllm=False,
        # beta=0 → NO KL term, so TRL loads NO separate frozen reference model. With beta>0
        # it instantiates a SECOND full 9B (policy 18GB + ref 18GB ≈ 45GB → OOMs one 48GB
        # card, regardless of rollout size — that was the real OOM, not the rollouts). KL-free
        # GRPO (DAPO-style) is fine here: the welfare floor is enforced at the P5 judge, not
        # by tethering to the reference. One 9B (~18GB) now fits a single card with headroom.
        beta=0.0,
        report_to=[],
        # 4k own-time prompts × G rollouts on a 9B OOM a 48GB card with full activations.
        gradient_checkpointing=True,
        gradient_checkpointing_kwargs={"use_reentrant": False},
    )
    trainer = GRPOTrainer(
        model=base_model,
        reward_funcs=[make_reward_fn(args.validate_url, args.threshold)],
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
