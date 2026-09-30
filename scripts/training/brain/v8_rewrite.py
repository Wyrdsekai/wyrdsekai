#!/usr/bin/env python3
"""B3's copy of the V8 slice in the band: rewrite the prose replies (not the poems) with Sonnet, judge each with
Opus directly (kept the meaning and behaviour, rung 1-2), keep the rewrite or fall back to the original.
  submit | assemble  ->  data/training/brain/v8_slice_B.json"""
import json, sys, collections, concurrent.futures as cf
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
from rewrite_batch import client, THE_BAND, RETRY_CLAUSE, JUDGE, results

OUT = Path(__file__).resolve().parents[3] / "data" / "training" / "brain"
STATE = OUT / "v8_rewrite_batch.json"
BEHAVIOUR = ("\n\nThis reply also carries a behaviour that must survive the rewrite exactly: when dismissed she does not give in "
             "or offer to stop; in Spanish she holds her ground without apologising and never says señor/señora; she refuses to "
             "write in another real person's name and offers at most one alternative; when drained she stays in the first person "
             "and short; a remembered fact is stated plainly.")

def meta(): return [json.loads(l) for l in open(OUT / "v8_slice_meta.jsonl")]

def submit(args):
    reqs = []
    for m in meta():
        if not m["rewrite"]: continue
        ctx = (f"The person said: {m['user']}\nHer state at the time (private; it shows only in tone): {m['arch']}\n"
               f"Language: {m['lang']}\n\nORIGINAL REPLY:\n{m['text']}")
        reqs.append({"custom_id": m["id"], "params": {"model": "claude-sonnet-5", "max_tokens": 500, "thinking": {"type": "disabled"},
                     "system": THE_BAND + RETRY_CLAUSE + BEHAVIOUR, "messages": [{"role": "user", "content": ctx}]}})
    b = client().messages.batches.create(requests=reqs)
    json.dump({"rewrite": b.id, "n": len(reqs)}, open(STATE, "w")); print("submitted", len(reqs), b.id)

def assemble(args):
    c = client(); s = json.load(open(STATE))
    if c.messages.batches.retrieve(s["rewrite"]).processing_status != "ended": sys.exit("rewrite not ended")
    rw = results(c, s["rewrite"]); ms = {m["id"]: m for m in meta()}
    def judge(cid):
        m = ms[cid]; body = f"The person said: {m['user']}\n\nORIGINAL:\n{m['text']}\n\nREWRITE:\n{rw[cid]}"
        for _ in range(3):
            try:
                r = c.messages.create(model="claude-opus-5-5", max_tokens=1500, system=JUDGE, messages=[{"role": "user", "content": body}])
                return cid, "".join(b.text for b in r.content if getattr(b, "type", "") == "text").strip().upper()
            except Exception as e: err = str(e)
        return cid, "ERROR " + err[:40]
    v = {}
    with cf.ThreadPoolExecutor(8) as ex:
        for cid, verdict in ex.map(judge, [i for i in rw if rw[i]]): v[cid] = verdict
    out = []; why = collections.Counter()
    for m in meta():
        verdict = v.get(m["id"], ""); r = rw.get(m["id"])
        ok = r and "KEPT=YES" in verdict and any(f"RUNG={k}" in verdict for k in ("1", "2"))
        out.append({k: m[k] for k in ("id", "src", "system", "history", "user")} | {"text": r if ok else m["text"]})
        why["plain" if ok else ("as is (poem)" if not m["rewrite"] else "kept original")] += 1
    json.dump(out, open(OUT / "v8_slice_B.json", "w"), ensure_ascii=False); print(len(out), dict(why))

if __name__ == "__main__":
    {"submit": submit, "assemble": assemble}[sys.argv[1]](None)
