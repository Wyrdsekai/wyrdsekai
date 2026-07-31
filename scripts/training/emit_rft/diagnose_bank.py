"""pre-GRPO signal diagnostic.

GRPO only learns where there is *reward variance* across a prompt's rollouts. Before
committing a full run we measure the base model's behaviour on the REAL rollout bank:
for a stratified sample of prompts, generate G completions each, score every one with
the SAME reward (reward.py -> RecipeValidateServer), and tabulate:

  - HIGH band (generativity >= threshold): emit-rate + valid-rate (the right move = emit)
  - LOW  band (generativity <  threshold): emit-rate              (the right move = rest)
  - per-prompt reward std (the GRPO learning signal)

Interpretation:
  * HIGH emit-rate already ~1.0 AND LOW emit-rate ~0.0  -> base already correct on these
    raw prompts; GRPO has little/no signal here (the gap lives in the full actor framing,
    not the captured prompt) -> reconsider corpus capture / fall back to runtime lever.
  * Mixed emit-rates / non-zero per-prompt std            -> real learnable signal -> run GRPO.

    python diagnose_bank.py --bank data/training/emit_rft/rollout_bank.jsonl \
        --base ~/wyrdsekai-9b-v4-merged --validate-url http://127.0.0.1:8077 \
        --sample 48 --gens 6 --temperature 0.9
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import reward as R


def stratified(rows, threshold, n):
    hi = [r for r in rows if float(r.get("generativity", 0)) >= threshold]
    lo = [r for r in rows if float(r.get("generativity", 0)) < threshold]
    k = max(1, n // 2)
    # even stride so we span langs/gaps, not just the head
    def stride(xs, k):
        if len(xs) <= k:
            return xs
        step = len(xs) / k
        return [xs[int(i * step)] for i in range(k)]
    return stride(hi, k), stride(lo, k)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--bank", required=True)
    ap.add_argument("--base", required=True)
    ap.add_argument("--adapter", default=None,
                    help="optional PEFT/LoRA adapter dir (e.g. a GRPO checkpoint) to load on "
                         "top of --base and merge before generating — lets us eval a checkpoint "
                         "without a separate merge step")
    ap.add_argument("--validate-url", default="http://127.0.0.1:8077")
    ap.add_argument("--threshold", type=float, default=R.ACT_THRESHOLD)
    ap.add_argument("--sample", type=int, default=48)
    ap.add_argument("--gens", type=int, default=6)
    ap.add_argument("--temperature", type=float, default=0.9)
    ap.add_argument("--max-new", type=int, default=320)
    args = ap.parse_args()

    rows = [json.loads(l) for l in Path(args.bank).read_text().splitlines() if l.strip()]
    hi, lo = stratified(rows, args.threshold, args.sample)
    print(f"[diag] bank={len(rows)} sampled HIGH={len(hi)} LOW={len(lo)} gens={args.gens} temp={args.temperature}")

    import torch
    from transformers import AutoModelForCausalLM, AutoTokenizer
    tok = AutoTokenizer.from_pretrained(args.base, trust_remote_code=True)
    model = AutoModelForCausalLM.from_pretrained(
        args.base, trust_remote_code=True, torch_dtype=torch.bfloat16, device_map="cuda")
    if args.adapter:
        from peft import PeftModel
        print(f"[diag] applying adapter {args.adapter} (merged for inference)")
        model = PeftModel.from_pretrained(model, args.adapter)
        model = model.merge_and_unload()
    model.eval()

    def gen_batch(messages, n, tools=None):
        # Pass the captured tool specs so the chat template surfaces shape_recipe — exactly
        # how TRL GRPO + the live serving path present them. Without this the model can't
        # emit a tool_call at all (the earlier 0-emit reading was this bug).
        text = tok.apply_chat_template(
            messages, tools=tools, tokenize=False, add_generation_prompt=True)
        enc = tok([text] * n, return_tensors="pt").to("cuda")
        with torch.no_grad():
            out = model.generate(
                **enc, max_new_tokens=args.max_new, do_sample=args.temperature > 0,
                temperature=max(args.temperature, 1e-5), top_p=0.95,
                pad_token_id=tok.pad_token_id or tok.eos_token_id)
        gen = out[:, enc["input_ids"].shape[1]:]
        return [tok.decode(g, skip_special_tokens=True) for g in gen]

    def measure(sample, band):
        per_prompt = []
        emit_n = valid_n = tot = 0
        for i, r in enumerate(sample):
            comps = gen_batch(r["messages"], args.gens, r.get("tools"))
            g = float(r.get("generativity", 0))
            rewards = []
            for c in comps:
                y = R.extract_shape_recipe_yaml(c)
                emitted = bool(y)
                validity = None
                if emitted:
                    try:
                        validity = R.validate_yaml(y, args.validate_url)
                    except Exception:
                        validity = None
                emit_n += emitted
                valid_n += bool(validity and validity.valid)
                tot += 1
                # score_decision wants (emitted, Validity|None, gen): emit-invalid still
                # scores as an emit (garbled/parse-only), NOT as a miss.
                rewards.append(R.score_decision(emitted, validity, g, args.threshold))
            mean = sum(rewards) / len(rewards)
            std = (sum((x - mean) ** 2 for x in rewards) / len(rewards)) ** 0.5
            per_prompt.append(std)
            print(f"[diag] {band} {i:2d} gen={g:.2f} emit={sum(1 for c in comps if R.extract_shape_recipe_yaml(c))}/{args.gens} reward_std={std:.3f}")
        nz = sum(1 for s in per_prompt if s > 1e-6)
        print(f"[diag] === {band}: emit-rate={emit_n/max(tot,1):.2f} valid-rate={valid_n/max(tot,1):.2f} "
              f"prompts-with-signal={nz}/{len(per_prompt)} ===")
        return emit_n / max(tot, 1), valid_n / max(tot, 1), nz, len(per_prompt)

    print("\n--- HIGH band (emit is right) ---")
    he, hv, hnz, hn = measure(hi, "HIGH")
    print("\n--- LOW band (rest is right) ---")
    le, lv, lnz, ln = measure(lo, "LOW")

    print("\n[diag] SUMMARY")
    print(json.dumps({
        "high_emit_rate": round(he, 3), "high_valid_rate": round(hv, 3),
        "low_emit_rate": round(le, 3),
        "signal_prompts": hnz + lnz, "total_prompts": hn + ln,
        "verdict": ("LOW-SIGNAL: base already ~correct on raw prompts; gap likely in actor framing"
                    if (hv > 0.8 and le < 0.2 and (hnz + lnz) < 0.25 * (hn + ln))
                    else "SIGNAL: reward variance present; GRPO can learn"),
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
