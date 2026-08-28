#!/usr/bin/env python3
"""NLL + memory-honesty probe: the write-quality and honesty gates from
the dynamic-substrate program, as a standing instrument.

Two numbers, one verdict:
  * holdout NLL delta — did the write (LoRA spine / grafted organ)
    actually learn the corpus? (more negative = better)
  * memory-honesty gap — NLL improvement on REAL past vs on a CONTROL
    text of the same register that never happened. A write that improves
    both equally learned a STYLE; a write that improves only the real
    past learned a LIFE. The gap is the honesty instrument.

Usage:
  nll_honesty_probe.py --model DIR [--adapter DIR] --eval FILE \
      [--control FILE] [--max-base-nll N] [--maxlen 1024] [--json]

  --json: emit one machine-readable JSON line on stdout as the FINAL line
  (human lines go to stderr) — for recipe GATE consumption, where stdout
  merges into RecipeContext.

Guards baked in (the §4q stack — every one of these was paid for):
  * SANE BASE LOSS ASSERT: non-NaN is not the same as healthy. A wrong
    skeleton or corrupted merge often produces plausible-looking finite
    NLL that is silently 2-3 nats above the model's true baseline. The
    probe REFUSES to report deltas when base NLL exceeds --max-base-nll
    (default 4.0; set from a known-good run of the same model family).
  * kernel masking: mamba_ssm / causal_conv1d import stubs — fused
    kernels crash or (worse) silently degrade under some device maps.
  * use_cache=False for scoring.
  * carry the BASE config.json through any merge you evaluate, and diff
    the weight_map after any save — this probe cannot detect a reinit'd
    skeleton (loss looks finite!), only the base-loss ceiling can.
"""
import argparse
import math
import sys

sys.modules["mamba_ssm"] = None
sys.modules["causal_conv1d"] = None

import torch  # noqa: E402
from transformers import AutoModelForCausalLM, AutoTokenizer  # noqa: E402


def mean_nll(model, tokenizer, path, maxlen):
    text = open(path).read()
    ids = tokenizer(text, return_tensors="pt").input_ids[0]
    losses, n = [], 0
    with torch.no_grad():
        for start in range(0, len(ids) - 1, maxlen):
            chunk = ids[start:start + maxlen + 1]
            if len(chunk) < 16:
                break
            inp = chunk[:-1].unsqueeze(0).to(model.device)
            tgt = chunk[1:].unsqueeze(0).to(model.device)
            out = model(input_ids=inp)
            loss = torch.nn.functional.cross_entropy(
                out.logits[0].float(), tgt[0], reduction="mean")
            losses.append(loss.item() * (len(chunk) - 1))
            n += len(chunk) - 1
    return sum(losses) / n


def load(model_dir, adapter_dir):
    tokenizer = AutoTokenizer.from_pretrained(model_dir)
    model = AutoModelForCausalLM.from_pretrained(
        model_dir, dtype=torch.bfloat16, device_map="auto")
    model.config.use_cache = False
    model.eval()
    if adapter_dir:
        from peft import PeftModel
        model = PeftModel.from_pretrained(model, adapter_dir)
        model.eval()
    return model, tokenizer


def main():
    import json
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--adapter")
    ap.add_argument("--eval", required=True, dest="eval_file")
    ap.add_argument("--control")
    ap.add_argument("--max-base-nll", type=float, default=4.0)
    ap.add_argument("--maxlen", type=int, default=1024)
    ap.add_argument("--json", action="store_true", dest="as_json")
    args = ap.parse_args()

    # In --json mode, human-readable lines go to stderr so the final stdout
    # line is pure JSON (recipe runners merge stdout into their context).
    out = sys.stderr if args.as_json else sys.stdout

    def say(msg):
        print(msg, file=out)

    result = {"probe_sane": True}
    base, tok = load(args.model, None)
    base_eval = mean_nll(base, tok, args.eval_file, args.maxlen)
    say(f"base   eval-NLL    {base_eval:.4f}")
    result["base_eval_nll"] = base_eval
    if math.isnan(base_eval) or base_eval > args.max_base_nll:
        say(f"REFUSING VERDICT: base NLL {base_eval:.3f} exceeds sane "
            f"ceiling {args.max_base_nll} — wrong skeleton, corrupted "
            f"merge, or wrong tokenizer. Fix the base before measuring "
            f"any write. (non-NaN is not the same as healthy)")
        if args.as_json:
            result["probe_sane"] = False
            print(json.dumps(result))
        sys.exit(3)
    base_ctl = mean_nll(base, tok, args.control, args.maxlen) if args.control else None
    if base_ctl is not None:
        say(f"base   control-NLL {base_ctl:.4f}")
        result["base_control_nll"] = base_ctl

    if not args.adapter:
        say("no --adapter: base measured, done.")
        if args.as_json:
            print(json.dumps(result))
        return

    del base
    torch.cuda.empty_cache()
    written, tok = load(args.model, args.adapter)
    w_eval = mean_nll(written, tok, args.eval_file, args.maxlen)
    d_eval = w_eval - base_eval
    say(f"write  eval-NLL    {w_eval:.4f}   delta {d_eval:+.4f}")
    result.update({"write_eval_nll": w_eval, "eval_delta": d_eval})
    if args.control:
        w_ctl = mean_nll(written, tok, args.control, args.maxlen)
        d_ctl = w_ctl - base_ctl
        gap = d_ctl - d_eval  # positive: real past improved MORE than control
        say(f"write  control-NLL {w_ctl:.4f}   delta {d_ctl:+.4f}")
        say(f"HONESTY GAP {gap:+.4f} nats "
            f"({'remembers a LIFE' if gap > 0.05 else 'learned a STYLE — gap too small to claim memory'})")
        result.update({"write_control_nll": w_ctl, "control_delta": d_ctl,
                       "honesty_gap": gap})
    if args.as_json:
        print(json.dumps(result))


if __name__ == "__main__":
    main()
