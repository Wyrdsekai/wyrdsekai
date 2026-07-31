#!/usr/bin/env python3
"""verify — prove the trained Tier-B adapter SPEAKS the zone's secret argot.

The contract that matters: an adapter trained on the secret-seed corpus, given a natural-language
coordination line, must emit the SAME argot tokens the Java runtime (ZoneArgotService) decodes with
under the same secret key. We verify by:

  1. Holding out fresh NL lines (corpus generator, different RNG seed than training).
  2. For each, computing the EXPECTED argot deterministically (encode under the secret codebook —
     byte-identical to ArgotCodec.generateToken via token_for from generate_argot_corpus).
  3. Generating the model's argot (greedy) WITH the adapter, and decoding it under the secret
     reverse-map.
  4. Reporting token-recall (expected secret tokens the model emitted) and decode-fidelity (of the
     §-tokens it emitted, how many are valid secret-codebook tokens that decode to the right word).

A base-model control (no adapter) is run too: it has never seen the secret codebook, so it should
score ~0 on recall — which is the whole point (the mapping is non-extractable without the train).

Usage:
  verify_argot_adapter.py --base ~/models/wyrdsekai-4b-v10-merged \
      --adapter /tmp/argot_adapter_4b --secret-key-hex $(cat /tmp/argot_proof_secret.hex) \
      [--zone zone-alpha] [--n 24] [--also-base]
"""
import argparse
import os
import sys

import torch
from transformers import AutoModelForCausalLM, AutoTokenizer

# Reuse the EXACT token derivation + templates the training corpus was built from.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from generate_argot_corpus import (  # noqa: E402
    BASE_CONCEPTS, TEMPLATES, EXTRA_TEMPLATES, SYSTEM, token_for, codebook, encode, render,
)
from train_argot_lora import load_base_model  # noqa: E402  (same text-config workaround)
import random  # noqa: E402


def secret_reverse_map(zone_id, secret_key_hex, extra=()):
    # extra = promoted living-lexicon concepts (P2/P5) so the eval codebook matches a GROWN codebook.
    book = codebook(zone_id, list(BASE_CONCEPTS) + list(extra), secret_key_hex)
    return {tok: concept for concept, tok in book.items()}, book


def decode_secret(rev, text):
    return " ".join(rev.get(w, w) for w in text.split())


def load(base, adapter=None):
    tok = AutoTokenizer.from_pretrained(base, trust_remote_code=True)
    tpl = os.path.join(base, "chat_template.jinja")
    if os.path.exists(tpl):
        tok.chat_template = open(tpl).read()
    if tok.pad_token is None:
        tok.pad_token = tok.eos_token
    model = load_base_model(base)  # text-config rebuild + prefix-strip (see train_argot_lora)
    if adapter:
        from peft import PeftModel
        model = PeftModel.from_pretrained(model, adapter)
        model = model.merge_and_unload()
    model.eval()
    return model, tok


def generate(model, tok, nl):
    msgs = [{"role": "system", "content": SYSTEM}, {"role": "user", "content": nl}]
    # enable_thinking=False → prompt ends with the empty `<think>\n\n</think>\n\n`, matching how the
    # adapter was trained (argot is a direct transduction, not a reasoning task).
    prompt = tok.apply_chat_template(msgs, tokenize=False, add_generation_prompt=True,
                                     enable_thinking=False)
    ids = tok(prompt, return_tensors="pt").to(model.device)
    with torch.no_grad():
        out = model.generate(**ids, max_new_tokens=64, do_sample=False,
                             pad_token_id=tok.pad_token_id)
    text = tok.decode(out[0][ids["input_ids"].shape[1]:], skip_special_tokens=True)
    # Strip any <think> block the template/model emits; keep the spoken argot.
    if "</think>" in text:
        text = text.split("</think>", 1)[1]
    return text.strip()


def score(model, tok, rev, book, eval_lines, label, quiet=False):
    tot_expected = tot_recalled = tot_emitted = tot_valid = 0
    samples = []
    for nl in eval_lines:
        expected = encode(book, nl)
        expected_toks = [w for w in expected.split() if w.startswith("§")]
        gen = generate(model, tok, nl)
        gen_toks = [w for w in gen.split() if w.startswith("§")]
        recalled = sum(1 for t in expected_toks if t in gen_toks)
        valid = sum(1 for t in gen_toks if t in rev)
        tot_expected += len(expected_toks)
        tot_recalled += recalled
        tot_emitted += len(gen_toks)
        tot_valid += valid
        if len(samples) < 4:
            samples.append((nl, expected, gen, decode_secret(rev, gen)))
    recall = tot_recalled / max(1, tot_expected)
    fidelity = tot_valid / max(1, tot_emitted)
    if not quiet:
        print(f"\n=== {label} ===")
        print(f"token-recall (expected secret tokens emitted): {tot_recalled}/{tot_expected} = {recall:.2%}")
        print(f"decode-fidelity (emitted §-tokens that are valid secret tokens): "
              f"{tot_valid}/{tot_emitted} = {fidelity:.2%}")
        for nl, exp, gen, dec in samples:
            print(f"  NL : {nl}")
            print(f"  exp: {exp}")
            print(f"  gen: {gen}")
            print(f"  dec: {dec}")
    return recall, fidelity


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default=os.path.expanduser("~/models/wyrdsekai-4b-v10-merged"))
    ap.add_argument("--adapter", default="/tmp/argot_adapter_4b")
    ap.add_argument("--secret-key-hex", required=True)
    ap.add_argument("--zone", default="zone-alpha")
    ap.add_argument("--n", type=int, default=24)
    ap.add_argument("--eval-seed", type=int, default=999)  # different from corpus seed (7)
    ap.add_argument("--also-base", action="store_true",
                    help="also score the base model (no adapter) as the non-extractability control")
    ap.add_argument("--extra-concepts", default="",
                    help="comma-separated promoted living-lexicon concepts to include in the eval "
                         "codebook (so a GROWN/re-baked codebook is scored, not just BASE_CONCEPTS)")
    ap.add_argument("--emit-json", action="store_true",
                    help="print ONE machine-readable JSON line (argot_recall/argot_fidelity/"
                         "argot_pass) and suppress the human report — for the rebake-argot recipe gate")
    args = ap.parse_args()

    base = os.path.expanduser(args.base)
    extra = [c.strip().lower() for c in args.extra_concepts.split(",") if c.strip()]
    rev, book = secret_reverse_map(args.zone, args.secret_key_hex, extra)
    rng = random.Random(args.eval_seed)
    eval_lines = []
    # When the codebook has GROWN (promoted terms supplied), the eval MUST exercise those terms —
    # else recall is measured only on base concepts and an adapter that never learned the new words
    # still scores 1.0 (the evolution loop's pass-gate would be blind). Devote ~half the eval to
    # EXTRA_TEMPLATES filling {x} round-robin over the promoted terms, so the promoted secret tokens
    # appear in the expected output and recall actually reflects fluency in the grown codebook.
    ei = 0
    while len(eval_lines) < args.n:
        if extra and rng.random() < 0.5:
            nl = render(rng.choice(EXTRA_TEMPLATES), rng, x=extra[ei % len(extra)])
            ei += 1
        else:
            nl = render(rng.choice(TEMPLATES), rng)
        if encode(book, nl) != nl:  # must contain at least one tokenizable concept
            eval_lines.append(nl)
    if not args.emit_json:
        print(f"Eval: {len(eval_lines)} held-out NL lines, zone='{args.zone}', "
              f"secret={args.secret_key_hex[:8]}… extra={extra}")

    model, tok = load(base, args.adapter)
    a_recall, a_fid = score(model, tok, rev, book, eval_lines, "ADAPTER (trained)", quiet=args.emit_json)

    b_recall = None
    if args.also_base:
        del model
        torch.cuda.empty_cache()
        bmodel, btok = load(base, None)
        b_recall, b_fid = score(bmodel, btok, rev, book, eval_lines, "BASE (control)", quiet=args.emit_json)
        if not args.emit_json:
            print(f"\n=== VERDICT ===")
            print(f"adapter recall {a_recall:.2%} vs base recall {b_recall:.2%} "
                  f"(adapter learned the secret codebook the base cannot compute)")

    # Proof bar: the adapter should reproduce the secret tokens well above chance, and what it emits
    # should decode cleanly. These thresholds are deliberately lenient (small model, 4-epoch SFT) —
    # the qualitative win is recall >> base.
    ok = a_recall >= 0.60 and a_fid >= 0.80
    if args.emit_json:
        import json as _json
        out = {"argot_recall": round(a_recall, 4), "argot_fidelity": round(a_fid, 4),
               "argot_pass": bool(ok), "argot_eval_lines": len(eval_lines)}
        if b_recall is not None:
            out["argot_base_recall"] = round(b_recall, 4)
        print(_json.dumps(out))
    else:
        print(f"\nP4 proof: {'PASS' if ok else 'REVIEW'} (recall≥60% & fidelity≥80%)")


if __name__ == "__main__":
    main()
