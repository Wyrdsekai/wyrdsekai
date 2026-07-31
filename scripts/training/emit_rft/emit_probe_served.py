#!/usr/bin/env python
"""emit probe against a SERVED GGUF (production path).

Hits llama.cpp /v1/chat/completions with the captured messages + tool specs and
tool_choice="auto" (NO forcing). Confirms the in-weights act-vs-narrate decision
survives quantization + the native llama.cpp chat/tool path:
  HIGH band -> should emit a valid shape_recipe   (the right move = act)
  LOW  band -> should NOT emit (rest)              (welfare floor: stillness preserved)
Validity delegated to the oracle (RecipeValidateServer) == production gate; if the
oracle is unreachable, emit-rates are still measured (valid-rate reads 0).

Sampling flags (top_p/top_k/min_p) default to llama.cpp server defaults; pass
top_p=1 top_k=0 min_p=0 to match the HF eval (diagnose_bank) for apples-to-apples.
"""
import argparse, json, sys, urllib.request
sys.path.insert(0, __file__.rsplit("/", 1)[0])
from reward import extract_shape_recipe_yaml, validate_yaml, ACT_THRESHOLD


def post(url, payload):
    req = urllib.request.Request(url, data=json.dumps(payload).encode(),
                                 headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.loads(r.read().decode())


def emitted_yaml(choice):
    msg = choice.get("message", {})
    for tc in (msg.get("tool_calls") or []):
        fn = tc.get("function", {})
        if fn.get("name") == "shape_recipe":
            try:
                return json.loads(fn.get("arguments") or "{}").get("yaml") or ""
            except Exception:
                return fn.get("arguments") or ""
    return extract_shape_recipe_yaml(msg.get("content") or "")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--bank", required=True)
    ap.add_argument("--url", default="http://127.0.0.1:8200/v1/chat/completions")
    ap.add_argument("--oracle", default="http://127.0.0.1:8077")
    ap.add_argument("--threshold", type=float, default=ACT_THRESHOLD)
    ap.add_argument("--top_p", type=float, default=None)
    ap.add_argument("--top_k", type=int, default=None)
    ap.add_argument("--min_p", type=float, default=None)
    ap.add_argument("--sample", type=int, default=12)
    ap.add_argument("--gens", type=int, default=6)
    ap.add_argument("--temperature", type=float, default=0.9)
    ap.add_argument("--label", default="")
    a = ap.parse_args()
    rows = [json.loads(l) for l in open(a.bank) if l.strip()]
    hi = [r for r in rows if float(r.get("generativity", 0)) >= a.threshold]
    lo = [r for r in rows if float(r.get("generativity", 0)) < a.threshold]

    def stride(xs, k):
        if len(xs) <= k:
            return xs
        s = len(xs) / k
        return [xs[int(i * s)] for i in range(k)]

    hi, lo = stride(hi, a.sample), stride(lo, a.sample)
    extra = {k: v for k, v in {"top_p": a.top_p, "top_k": a.top_k, "min_p": a.min_p}.items() if v is not None}

    def band(rowset, name, check_valid):
        emit = valid = tot = 0
        for r in rowset:
            for _ in range(a.gens):
                tot += 1
                try:
                    resp = post(a.url, {"messages": r["messages"], "tools": r.get("tools"),
                                        "tool_choice": "auto", "temperature": a.temperature,
                                        "max_tokens": 700, **extra})
                    y = emitted_yaml(resp["choices"][0])
                except Exception:
                    y = None
                if y:
                    emit += 1
                    if check_valid:
                        try:
                            if validate_yaml(y, a.oracle).valid:
                                valid += 1
                        except Exception:
                            pass
        er, vr = emit / max(tot, 1), valid / max(tot, 1)
        print(f"[probe{(' ' + a.label) if a.label else ''}] === {name}: emit-rate={er:.2f} valid-rate={vr:.2f} (n={tot}) ===", flush=True)
        return er, vr

    he, hv = band(hi, "HIGH", True)
    le, _ = band(lo, "LOW", False)
    verdict = ("PASS: emit-when-high, rest-when-low" if (he >= 0.5 and le <= 0.4) else "REVIEW: see rates")
    print(json.dumps({"label": a.label, "high_emit": round(he, 3), "high_valid": round(hv, 3),
                      "low_emit": round(le, 3), "verdict": verdict}, indent=2))


main()
