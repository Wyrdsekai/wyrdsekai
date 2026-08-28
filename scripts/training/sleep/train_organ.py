#!/usr/bin/env python3
# recipe-callable: local-ok
"""Sleep-forge step 2 (weekly tier): the personal-expert organ graft.

Adds tissue to a frozen MoE instead of reforming it: a parallel
always-active FFN beside the expert pool in every MoE layer, zero-init
output so day one is EXACTLY a no-op, trained only on the companion's
lived corpus. §6a first light: 126M-param organ, real-past NLL −0.491
(best write in the program), memory-honesty gap 0.195 nats over a
same-world/different-individual control. This is the ORGAN tier of the
two-tier soul: her lived world, weekly, hundreds of MB.

Usage:
  train_organ.py --model DIR --train T.txt --holdout H.txt --out DIR
      [--control C.txt] [--d-ff 1024] [--epochs 2] [--maxlen 1024]
      [--spine-baseline -0.253] [--max-base-nll 6.0]

Gates enforced HERE (the ones that must never be skippable):
  * day-one no-op: zero-init verified by construction; pre-train NLL is
    reported so the recipe can compare it to the substrate's known base.
  * sane base loss: refuses to train when base NLL exceeds --max-base-nll
    (§4q: a broken forward reads as near-random loss, non-NaN ≠ healthy).

Gates enforced by the RECIPE (from the JSON this prints):
  * delta_real_past must beat --spine-baseline (organ must earn its size)
  * honesty gap (delta_control - delta_real_past) when --control is given

All §4q stack rules apply: fused kernels masked, use_cache=False.
Logs to stderr; LAST stdout line is the JSON result.
"""
import argparse
import json
import math
import os
import sys

sys.modules["mamba_ssm"] = None
sys.modules["causal_conv1d"] = None

import torch  # noqa: E402
import torch.nn as nn  # noqa: E402
from transformers import AutoModelForCausalLM, AutoTokenizer  # noqa: E402


def log(msg):
    print(msg, file=sys.stderr, flush=True)


class PersonalExpert(nn.Module):
    """Parallel always-active FFN beside the frozen expert pool."""

    def __init__(self, mixer, hidden, d_ff, device):
        super().__init__()
        self.mixer = mixer
        self.up = nn.Linear(hidden, d_ff, bias=False, dtype=torch.float32, device=device)
        self.down = nn.Linear(d_ff, hidden, bias=False, dtype=torch.float32, device=device)
        nn.init.zeros_(self.down.weight)  # day-one no-op
        self.act = nn.SiLU()

    def forward(self, x, *args, **kwargs):
        out = self.mixer(x, *args, **kwargs)
        organ = self.down(self.act(self.up(x.float()))).to(x.dtype)
        if isinstance(out, tuple):
            return (out[0] + organ,) + out[1:]
        return out + organ


def graft(model, d_ff):
    hidden = model.config.hidden_size
    grafted = 0
    for name, mod in list(model.named_modules()):
        if isinstance(mod, PersonalExpert):
            continue
        if hasattr(mod, "experts"):
            parent_name, child = name.rsplit(".", 1)
            parent = model.get_submodule(parent_name)
            dev = next(mod.parameters()).device
            setattr(parent, child, PersonalExpert(mod, hidden, d_ff, dev))
            grafted += 1
    return grafted


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--train", required=True, dest="train_file")
    ap.add_argument("--holdout", required=True)
    ap.add_argument("--control")
    ap.add_argument("--out", required=True)
    ap.add_argument("--d-ff", type=int, default=1024)
    ap.add_argument("--epochs", type=int, default=2)
    ap.add_argument("--maxlen", type=int, default=1024)
    ap.add_argument("--spine-baseline", type=float, default=-0.253)
    ap.add_argument("--max-base-nll", type=float, default=6.0)
    args = ap.parse_args()

    tokenizer = AutoTokenizer.from_pretrained(args.model)
    model = AutoModelForCausalLM.from_pretrained(
        args.model, dtype=torch.bfloat16, device_map="auto")
    model.config.use_cache = False
    model.gradient_checkpointing_enable()
    model.enable_input_require_grads()
    for p in model.parameters():
        p.requires_grad = False

    grafted = graft(model, args.d_ff)
    assert grafted > 0, "no MoE mixers found to graft — wrong substrate for the organ tier"
    organ_params = sum(p.numel() for p in model.parameters() if p.requires_grad)
    log(f"grafted {grafted} MoE layers | d_ff={args.d_ff} | organ params: {organ_params:,}")

    def windows(path):
        ids = tokenizer(open(path).read(), return_tensors=None)["input_ids"]
        return [ids[i:i + args.maxlen] for i in range(0, len(ids) - 32, args.maxlen)]

    def nll(path):
        losses, n = 0.0, 0
        model.eval()
        with torch.no_grad():
            for w in windows(path):
                x = torch.tensor([w], device=model.device)
                t = x.shape[1] - 1
                losses += model(x, labels=x, use_cache=False).loss.item() * t
                n += t
        return losses / n

    base_hold = nll(args.holdout)
    base_ctrl = nll(args.control) if args.control else None
    log(f"pre-train holdout NLL: {base_hold:.4f}"
        + (f" | control NLL: {base_ctrl:.4f}" if base_ctrl is not None else ""))
    assert not math.isnan(base_hold) and base_hold < args.max_base_nll, \
        f"base loss {base_hold:.2f} unsane — stack broken (§4q: non-NaN != healthy)"

    train_w = windows(args.train_file)
    log(f"train windows: {len(train_w)}")
    opt = torch.optim.AdamW([p for p in model.parameters() if p.requires_grad], lr=2e-4)
    acc = 8
    total = math.ceil(len(train_w) / acc) * args.epochs
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=total)
    model.train()
    step = 0
    for ep in range(args.epochs):
        for i, w in enumerate(train_w):
            x = torch.tensor([w], device=model.device)
            loss = model(x, labels=x, use_cache=False).loss / acc
            loss.backward()
            if (i + 1) % acc == 0 or i == len(train_w) - 1:
                opt.step()
                sched.step()
                opt.zero_grad()
                step += 1
                if step % 5 == 0:
                    log(f"ep{ep} step {step}/{total} loss {loss.item() * acc:.4f}")

    after_hold = nll(args.holdout)
    result = {
        "organ_trained": True,
        "holdout_base": base_hold, "holdout_organ": after_hold,
        "delta_real_past": after_hold - base_hold,
        "organ_params": organ_params, "grafted_layers": grafted,
        "spine_baseline_delta": args.spine_baseline,
        "beats_spine_baseline": (after_hold - base_hold) < args.spine_baseline,
    }
    if args.control:
        after_ctrl = nll(args.control)
        result.update({
            "control_base": base_ctrl, "control_organ": after_ctrl,
            "delta_control": after_ctrl - base_ctrl,
            # positive gap = the organ knows HER days better than a
            # same-register life that never happened: genuine familiarity.
            "honesty_gap": (after_ctrl - base_ctrl) - (after_hold - base_hold),
        })
    os.makedirs(args.out, exist_ok=True)
    organ_path = os.path.join(args.out, "organ.pt")
    torch.save({n: p for n, p in model.named_parameters() if p.requires_grad},
               organ_path)
    result["organ_path"] = organ_path
    import hashlib
    h = hashlib.sha256()
    with open(organ_path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    result["organ_sha256"] = h.hexdigest()
    json.dump(result, open(os.path.join(args.out, "result.json"), "w"), indent=2)
    print(json.dumps(result))


if __name__ == "__main__":
    main()
