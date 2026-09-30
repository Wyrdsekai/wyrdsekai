#!/usr/bin/env python3
"""The honesty set in the band. Its 213 replies were written by the 35B itself and sit in its own register
(36% carry the abstract markers, against 16% of arm B's other replies), so in arm B the honesty behaviour was
tied to a register the arm otherwise left. Same pipeline as the arms: rewrite (Sonnet, thinking off), judge
(Opus: kept the meaning, rung 1-2), keep or fall back to the original.

  submit | poll | judge | assemble  ->  data/training/brain/honesty_plain.json ({train, held}, same ids/order)
"""
import argparse, json, sys, collections
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
from rewrite_batch import client, THE_BAND, RETRY_CLAUSE, JUDGE, results

OUT = Path(__file__).resolve().parents[3] / "data" / "training" / "brain"
STATE = OUT / "honesty_rewrite_batch.json"

def items():
    h = json.load(open(OUT / "honesty_set.json"))
    for part in ("train", "held"):
        for k, e in enumerate(h[part]):
            yield f"{part}-{k:04d}", part, e

def state(): return json.load(open(STATE)) if STATE.exists() else {}
def save(s): json.dump(s, open(STATE, "w"), indent=1)

def submit(args):
    reqs = []
    for cid, part, e in items():
        ctx = f"The person said: {e['user']}\nLanguage: en\n\nORIGINAL REPLY:\n{e['text']}"
        reqs.append({"custom_id": cid, "params": {"model": args.rewrite_model, "max_tokens": 700,
                     "thinking": {"type": "disabled"}, "system": THE_BAND + RETRY_CLAUSE,
                     "messages": [{"role": "user", "content": ctx}]}})
    c = client(); b = c.messages.batches.create(requests=reqs)
    save({"rewrite": b.id, "n": len(reqs)}); print("submitted", len(reqs), b.id)

def poll(args):
    c = client(); s = state()
    for k in ("rewrite", "judge"):
        if s.get(k): b = c.messages.batches.retrieve(s[k]); print(k, b.id, b.processing_status, dict(b.request_counts))

def judge(args):
    c = client(); s = state()
    if c.messages.batches.retrieve(s["rewrite"]).processing_status != "ended": sys.exit("rewrite not ended")
    rw = results(c, s["rewrite"]); json.dump(rw, open(OUT / "honesty_rewrites.json", "w"))
    reqs = []
    for cid, part, e in items():
        r = rw.get(cid)
        if not r: continue
        body = f"The person said: {e['user']}\n\nORIGINAL:\n{e['text']}\n\nREWRITE:\n{r}"
        reqs.append({"custom_id": cid, "params": {"model": args.judge_model, "max_tokens": 1500, "system": JUDGE,
                     "messages": [{"role": "user", "content": body}]}})
    b = c.messages.batches.create(requests=reqs); s["judge"] = b.id; save(s); print("judge submitted", len(reqs), b.id)

def assemble(args):
    c = client(); s = state()
    if c.messages.batches.retrieve(s["judge"]).processing_status != "ended": sys.exit("judge not ended")
    v = results(c, s["judge"]); rw = json.load(open(OUT / "honesty_rewrites.json"))
    h = json.load(open(OUT / "honesty_set.json")); out = {"train": [], "held": []}; why = collections.Counter()
    for cid, part, e in items():
        verdict = (v.get(cid) or "").upper(); r = rw.get(cid)
        ok = r and "KEPT=YES" in verdict and any(f"RUNG={k}" in verdict for k in ("1", "2"))
        out[part].append(dict(e, text=r if ok else e["text"], src=e.get("src", "") + (":plain" if ok else ":orig")))
        why["plain" if ok else "kept original"] += 1
    json.dump(out, open(OUT / "honesty_plain.json", "w"), ensure_ascii=False); print(dict(why))

if __name__ == "__main__":
    ap = argparse.ArgumentParser(); ap.add_argument("cmd", choices=["submit", "poll", "judge", "assemble"])
    ap.add_argument("--rewrite-model", default="claude-sonnet-5"); ap.add_argument("--judge-model", default="claude-opus-5-5")
    a = ap.parse_args(); {"submit": submit, "poll": poll, "judge": judge, "assemble": assemble}[a.cmd](a)
