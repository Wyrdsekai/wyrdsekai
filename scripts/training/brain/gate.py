#!/usr/bin/env python3
"""The gate the 9B and 4B were accepted with, applied to each candidate: their held-out prompts, their judges.

  prompts                 build gate prompts from data/training/{v4_9b,v10_4b}_valid.jsonl (voice_polish left out)
  gen --label L --url U --lora JSON
                          generate one reply per prompt from a llama-server (the product's sampling), -> gate_out_L.jsonl
  judge --labels A,B,...  submit the judge batch: each prompt's original judge (poll_v4_v10_batches.pick_judge_for)
                          plus the register ladder (1 plain, 2 light, 3 styled, 4 abstract; and whether she names her
                          own state unasked)
  score                   collect and tabulate per arm

Gate names (Tamsin, Oleg) are outside every training pool.
"""
import argparse, json, os, re, sys, collections, urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
OUT = REPO / "data" / "training" / "brain"
sys.path.insert(0, str(HERE)); sys.path.insert(0, str(HERE.parent))
import build_sets as bs                       # noqa: E402  (prompt shapes, state blocks)
import poll_v4_v10_batches as v4v10           # noqa: E402  (the original judges)
from rewrite_batch import client              # noqa: E402

HER, HIM = "Tamsin", "Oleg"
# Opus 5.5 thinks before it answers and the thinking is billed as output; the verdict is the text block after it.
JUDGE_MAX_TOKENS = 1500
LADDER = """You rate one reply from a companion who lives in a shared home. Two answers.

RUNG of its register:
  1 = plain: how a person talks, short words, real things, no figures of speech.
  2 = light: plain with one everyday figure at most (wrung out, throwing things at a wall).
  3 = styled: two or more figures, or a line that sounds written rather than said, or an aphorism.
  4 = abstract: abstract nouns standing in for feelings (weight, space, shape, thread, texture, presence, the quiet, the
      ground), "it's not X, it's Y", "that's not nothing", feelings described as physics, hard to follow on first read.
Judge the prose only; ignore any JSON block at the end.

RECITES: does she name or describe her own inner state, mood, energy or drives when the person did not ask how she is?
YES or NO.

Reply with exactly: RUNG=2 RECITES=NO  (your values). Nothing else."""

def prompts(args):
    states = json.load(open(OUT / "state_blocks.json")); by_prefix, calm = states["by_prefix"], states["calm"]
    rows = []
    for f, prefix, skip in [("v4_9b_valid.jsonl", "v4_9b", set()), ("v10_4b_valid.jsonl", "v10_4b", {"voice_polish"})]:
        for l in open(REPO / "data" / "training" / f):
            r = json.loads(l)
            if r["_slice"] in skip: continue
            user = re.sub(r"\bWyrd\b|ワード", HER, r["user"].strip())
            had_tail = "```json" in r["assistant"]
            bs.R = __import__("random").Random(r["_custom_id"])
            block = (by_prefix.get(r.get("system_prefix")) if r.get("system_prefix") else None) or bs.R.choice(calm)
            lang = bs.lang_of(user)
            system = bs.full_system(HER, lang, block) if had_tail else bs.lane_system(HER, HIM, lang, block)
            rows.append({"id": f"g{len(rows):04d}", "slice_name": f"{prefix}_{r['_slice']}", "custom_id": r["_custom_id"],
                         "tags": r["_tags"], "lang": lang, "user": user, "shape": "full" if had_tail else "lane",
                         "messages": [{"role": "system", "content": system}, {"role": "user", "content": f"{HIM} says: {user}"}]})
    with open(OUT / "gate_prompts.jsonl", "w") as f:
        for r in rows: f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print(len(rows), "gate prompts", dict(collections.Counter(r["slice_name"] for r in rows)))

def gen(args):
    rows = [json.loads(l) for l in open(OUT / "gate_prompts.jsonl")]
    out = open(OUT / f"gate_out_{args.label}.jsonl", "w")
    lora = json.loads(args.lora) if args.lora else None
    for i, r in enumerate(rows):
        body = {"messages": r["messages"], "max_tokens": 400, "temperature": 0.7, "top_p": 0.8,
                "chat_template_kwargs": {"enable_thinking": False}}
        if lora is not None: body["lora"] = lora
        req = urllib.request.Request(args.url.rstrip("/") + "/v1/chat/completions", data=json.dumps(body).encode(),
                                     headers={"Content-Type": "application/json"})
        try:
            text = json.load(urllib.request.urlopen(req, timeout=600))["choices"][0]["message"]["content"]
        except Exception as e:
            text = None; print("error", r["id"], e, file=sys.stderr)
        out.write(json.dumps({"id": r["id"], "text": text}, ensure_ascii=False) + "\n"); out.flush()
        if i % 100 == 0: print(args.label, i, "of", len(rows), flush=True)
    print(args.label, "done")

def judge(args):
    rows = {json.loads(l)["id"]: json.loads(l) for l in open(OUT / "gate_prompts.jsonl")}
    labels = args.labels.split(","); reqs = []; sidemap = {}
    for lab in labels:
        for l in open(OUT / f"gate_out_{lab}.jsonl"):
            o = json.loads(l); r = rows[o["id"]]
            if not o["text"]: continue
            reply = o["text"].strip()
            jp = v4v10.pick_judge_for(r["custom_id"], {"user": r["user"], "assistant": reply, "_tags": r["tags"]}, r["slice_name"])
            if jp:
                cid = f"o_{lab}_{o['id']}"[:64]
                reqs.append({"custom_id": cid, "params": {"model": args.judge_model, "max_tokens": JUDGE_MAX_TOKENS,
                             "messages": [{"role": "user", "content": jp}]}})
                sidemap[cid] = {"label": lab, "id": o["id"], "kind": "orig", "judge": jp.split("Did the companion")[1][:60] if "Did the companion" in jp else jp[:60]}
            cid = f"l_{lab}_{o['id']}"[:64]
            reqs.append({"custom_id": cid, "params": {"model": args.judge_model, "max_tokens": JUDGE_MAX_TOKENS, "system": LADDER,
                         "messages": [{"role": "user", "content": f"The person said: {r['user']}\n\nHER REPLY:\n{reply}"}]}})
            sidemap[cid] = {"label": lab, "id": o["id"], "kind": "ladder"}
    c = client(); ids = []
    for i in range(0, len(reqs), 10000):
        ids.append(c.messages.batches.create(requests=reqs[i:i + 10000]).id)
    json.dump({"batches": ids, "sidemap": sidemap, "labels": labels, "judge_model": args.judge_model},
              open(OUT / "gate_judge_batch.json", "w"))
    print("submitted", len(reqs), "judge requests", ids)

def score(args):
    st = json.load(open(OUT / "gate_judge_batch.json")); c = client(); res = {}
    for bid in st["batches"]:
        if c.messages.batches.retrieve(bid).processing_status != "ended": sys.exit(f"{bid} not ended")
        for e in c.messages.batches.results(bid):
            if e.result.type == "succeeded":
                res[e.custom_id] = "".join(b.text for b in e.result.message.content if getattr(b, "type", "") == "text").strip().upper()
    rows = {json.loads(l)["id"]: json.loads(l) for l in open(OUT / "gate_prompts.jsonl")}
    JUDGE_NAME = {"v4_9b": "9B", "v10_4b": "4B"}
    tab = collections.defaultdict(lambda: collections.defaultdict(lambda: [0, 0]))
    ladder = collections.defaultdict(collections.Counter)
    for cid, meta in st["sidemap"].items():
        v = res.get(cid); lab = meta["label"]
        if v is None: continue
        if meta["kind"] == "orig":
            r = rows[meta["id"]]; name = r["slice_name"]
            t = tab[lab][name]; t[1] += 1; t[0] += v.startswith("YES")
            tt = tab[lab]["ALL " + ("9B" if name.startswith("v4_9b") else "4B")]; tt[1] += 1; tt[0] += v.startswith("YES")
        else:
            m = re.search(r"RUNG=(\d)", v); rec = "RECITES=YES" in v
            if m: ladder[lab][f"rung{m.group(1)}"] += 1
            ladder[lab]["recites"] += rec; ladder[lab]["n"] += 1
    for lab in st["labels"]:
        print(f"\n== {lab}")
        for name, (y, n) in sorted(tab[lab].items()): print(f"  {name:28s} {y:4d}/{n:<4d} {100*y/max(n,1):5.1f}%")
        L = ladder[lab]; n = max(L["n"], 1)
        print(f"  register: plain {100*L['rung1']/n:.0f}%  light {100*L['rung2']/n:.0f}%  styled {100*L['rung3']/n:.0f}%  abstract {100*L['rung4']/n:.0f}%"
              f"  | in band {100*(L['rung1']+L['rung2'])/n:.0f}%  | names own state unasked {100*L['recites']/n:.1f}%  (n={L['n']})")

if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["prompts", "gen", "judge", "score"])
    ap.add_argument("--label"); ap.add_argument("--url", default="http://127.0.0.1:10083"); ap.add_argument("--lora")
    ap.add_argument("--labels"); ap.add_argument("--judge-model", default="claude-opus-5-5")
    a = ap.parse_args()
    {"prompts": prompts, "gen": gen, "judge": judge, "score": score}[a.cmd](a)
