#!/usr/bin/env python3
"""#913 eval — per-token NLL split by role (agent vs world).

Loads a base model (optionally + a LoRA adapter) and computes, over a held-out
set of ReAct agent transcripts, the mean negative log-likelihood the model
assigns to:
  - AGENT tokens  : spans the agent itself wrote (assistant role) — its policy.
  - WORLD tokens  : spans produced by the environment/user (system+user role),
                    i.e. tool_responses and the bondholder's prompts.

de Freitas & Ortega: standard SFT on agent transcripts treats the agent's own
outputs as observational evidence, inflating agent-token confidence (a
self-confirming delusion) without improving world prediction. Interventional
SFT masks agent tokens from the loss, so it should learn world dynamics
(lower WORLD nll on held-out) WITHOUT pathologically driving down its own
AGENT nll.

The #913 question: does that separation survive at 4B on an already
instruct+voice-aligned base? Compare base vs standard-SFT vs interventional-SFT:
  - standard:        AGENT nll ↓↓ (memorizes policy), WORLD nll ~flat
  - interventional:  WORLD nll ↓,   AGENT nll ~flat (no self-confirmation)

Usage:
  python eval_logprob_delta.py --model Qwen/Qwen3.5-4B \\
      --adapter /tmp/iv_standard --data <held_out.jsonl> --label standard
"""
import argparse
import json
import sys
import os

import torch
from transformers import AutoModelForCausalLM, AutoTokenizer

# reuse the robust marker-based role attribution from the trainer
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from ssd_finetune import role_per_token  # noqa: E402


@torch.no_grad()
def transcript_nll(model, tokenizer, messages, max_length, device):
    """Return (agent_nll_sum, agent_tok, world_nll_sum, world_tok) for one transcript."""
    text = tokenizer.apply_chat_template(
        messages, tokenize=False, add_generation_prompt=False)
    enc = tokenizer(text, truncation=True, max_length=max_length, return_tensors="pt")
    input_ids = enc["input_ids"].to(device)
    out = model(input_ids=input_ids)
    logits = out.logits[0, :-1, :].float()           # predict token t+1 from t
    targets = input_ids[0, 1:]                        # shifted
    logprobs = torch.log_softmax(logits, dim=-1)
    tok_lp = logprobs[torch.arange(targets.size(0)), targets]  # per-position logprob

    # role of each (target) token, marker-based on the full render
    role_of = role_per_token(tokenizer, text, max_length)
    role_of = role_of + [None] * (input_ids.size(1) - len(role_of))

    a_sum = a_tok = w_sum = w_tok = 0.0
    for pos in range(targets.size(0)):
        r = role_of[pos + 1]
        nll = -tok_lp[pos].item()
        if r == "assistant":
            a_sum += nll; a_tok += 1
        elif r in ("system", "user"):
            w_sum += nll; w_tok += 1
    return a_sum, a_tok, w_sum, w_tok


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--adapter", default=None, help="LoRA adapter dir (omit for base)")
    ap.add_argument("--data", required=True, help="held-out JSONL of {messages:[...]}")
    ap.add_argument("--label", default="model")
    ap.add_argument("--max-length", type=int, default=1024)
    ap.add_argument("--limit", type=int, default=0, help="cap transcripts (0=all)")
    args = ap.parse_args()

    device = "cuda" if torch.cuda.is_available() else "cpu"
    tok = AutoTokenizer.from_pretrained(args.model, trust_remote_code=True)
    if tok.pad_token is None:
        tok.pad_token = tok.eos_token
    model = AutoModelForCausalLM.from_pretrained(
        args.model, torch_dtype=torch.bfloat16, trust_remote_code=True).to(device)
    if args.adapter:
        from peft import PeftModel
        model = PeftModel.from_pretrained(model, args.adapter)
    model.eval()

    A_sum = A_tok = W_sum = W_tok = 0.0
    n = 0
    with open(args.data) as f:
        for line in f:
            msgs = json.loads(line)["messages"]
            a_s, a_t, w_s, w_t = transcript_nll(model, tok, msgs, args.max_length, device)
            A_sum += a_s; A_tok += a_t; W_sum += w_s; W_tok += w_t
            n += 1
            if args.limit and n >= args.limit:
                break

    agent_nll = A_sum / max(A_tok, 1)
    world_nll = W_sum / max(W_tok, 1)
    print(json.dumps({
        "label": args.label,
        "adapter": args.adapter,
        "transcripts": n,
        "agent_tokens": int(A_tok),
        "world_tokens": int(W_tok),
        "agent_nll": round(agent_nll, 5),
        "world_nll": round(world_nll, 5),
        "agent_ppl": round(float(torch.exp(torch.tensor(agent_nll))), 4),
        "world_ppl": round(float(torch.exp(torch.tensor(world_nll))), 4),
    }))


if __name__ == "__main__":
    main()
