"""Diagnose the GRPO no-gradient collapse (frac_reward_zero_std=1.0).

GRPO learns from WITHIN-prompt reward variance. The run showed frac_reward_zero_std=1.0
(every prompt's 8 rollouts got the identical reward) -> zero advantage -> loss 0.0.
Two possible causes, distinguished here:

  (A) overfit-peaked policy: the warmup-SFT memorized one completion per prompt, so all 8
      rollouts are behaviorally identical even at temp 0.9. Fix = re-warm lighter (fewer epochs).
  (B) reward too coarse: rollouts ARE diverse but all land in the same reward bucket. Fix = reward shaping.

For each of a few representative bank prompts, sample G=8 at temp 0.9 and temp 1.3, score each
with reward_agency, and print the per-prompt reward set (the within-group spread) + sample text.
If temp 1.3 restores spread -> just raise rollout temperature. If it stays collapsed -> re-warm lighter.

    CUDA_VISIBLE_DEVICES=0 python scripts/training/v7_agency/probe_warmed_diversity.py \
        --base ~/wyrdsekai-9b-v7-warmed --bank scripts/training/v7_agency/rollout_bank.jsonl
"""
from __future__ import annotations
import argparse, json, os, sys
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import reward_agency as RA


def pick_rows(rows, k_each=1):
    """Pick representative rows: high-drive act-match, low-drive rest, discrim, imagine."""
    buckets = {"act": [], "rest": [], "other": []}
    for r in rows:
        dv = float(r.get("drive_value", 0.0))
        mt = str(r.get("match_tool", ""))
        if mt and dv >= 0.6:
            buckets["act"].append(r)
        elif dv < 0.4 and not mt:
            buckets["rest"].append(r)
        else:
            buckets["other"].append(r)
    out = []
    out += buckets["act"][:3]
    out += buckets["rest"][:2]
    out += buckets["other"][:3]
    return out, {k: len(v) for k, v in buckets.items()}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True)
    ap.add_argument("--bank", default="scripts/training/v7_agency/rollout_bank.jsonl")
    ap.add_argument("--gens", type=int, default=8)
    ap.add_argument("--max-new", type=int, default=200)
    ap.add_argument("--temps", default="0.9,1.3")
    args = ap.parse_args()

    import torch
    from transformers import AutoTokenizer, AutoModelForCausalLM

    rows = [json.loads(l) for l in Path(args.bank).read_text().splitlines() if l.strip()]
    probe, counts = pick_rows(rows)
    print(f"[probe] bank={len(rows)} buckets={counts} probing {len(probe)} prompts x G={args.gens} x temps={args.temps}\n")

    tok = AutoTokenizer.from_pretrained(args.base, trust_remote_code=True)
    model = AutoModelForCausalLM.from_pretrained(args.base, dtype=torch.bfloat16, trust_remote_code=True).to("cuda")
    model.eval()

    temps = [float(t) for t in args.temps.split(",")]
    for i, r in enumerate(probe):
        msgs, dv, mt, tools = r["messages"], float(r.get("drive_value", 0.0)), str(r.get("match_tool", "")), r.get("tools")
        enc = tok.apply_chat_template(msgs, tools=tools, add_generation_prompt=True, return_tensors="pt")
        ids = (enc["input_ids"] if hasattr(enc, "keys") else enc).to("cuda")
        plen = ids.shape[1]
        print(f"=== prompt {i}  drive={dv:.2f}  match_tool={mt or '(rest)'} ===")
        for T in temps:
            with torch.no_grad():
                out = model.generate(ids, do_sample=True, temperature=T, top_p=1.0,
                                     num_return_sequences=args.gens, max_new_tokens=args.max_new,
                                     pad_token_id=tok.pad_token_id or tok.eos_token_id)
            texts = [tok.decode(o[plen:], skip_special_tokens=True) for o in out]
            scores = [RA.score(t, dv, mt) for t in texts]
            uniq_txt = len(set(t.strip()[:120] for t in texts))
            uniq_rew = sorted(set(round(s, 2) for s in scores))
            within_std = (sum((s - sum(scores)/len(scores))**2 for s in scores)/len(scores))**0.5
            print(f"  T={T}: rewards={[round(s,2) for s in scores]}  within_std={within_std:.3f}  "
                  f"uniq_reward={uniq_rew}  uniq_text={uniq_txt}/{args.gens}")
            # one sample
            print(f"    sample[0]: {texts[0].strip()[:160]!r}")
        print()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
