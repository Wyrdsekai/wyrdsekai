#!/usr/bin/env python3
"""Arm B of the brain adapter: the same examples as arm A with the prose rewritten into plain speech, through
the Anthropic Message Batches API. The 9B's action JSON is untouched.

  submit    one request per example in armA_meta.jsonl (rewrite model) -> brain/rewrite_batch.json
  poll      print the batch's status
  judge     submit the judge batch over the rewrites (judge model): kept the meaning and behaviour? and
            which rung of the ladder (1 plain, 2 light, 3 styled, 4 abstract)
  assemble  keep rewrites at rung 1-2 that kept the meaning; examples that failed are removed from BOTH
            arms so the two sets hold the same examples -> armA_final.json, armB.json

Key: ANTHROPIC_API_KEY, or ~/claudeapi.txt. Models: --rewrite-model (default claude-sonnet-5),
--judge-model (default claude-opus-5-5). The two are different on purpose.
"""
import argparse, json, os, sys, time, collections, hashlib
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
OUT = REPO / "data" / "training" / "brain"
STATE = OUT / "rewrite_batch.json"

THE_BAND = """You rewrite one reply from a companion who lives in a shared home, so that it sounds like a person talking, and
nothing else changes: same language, same meaning, same decision (a refusal stays a refusal, help stays help, a
question back stays), same warmth, about the same length (shorter is fine), first person.

The target register, with examples. Plain (rung 1) is the default; light (rung 2) is the ceiling.

Plain:
- "I had a rough day." -> "Oh no. What happened?"
- "hey, how have you been today?" (tired) -> "Tired, honestly. Quiet day. I went to the Study and back and that was about it. How was yours?"
- "what have you been up to?" (after reading) -> "I read about why bread goes stale. It isn't drying out, the starch changes. Now I want to know if freezing stops it."
- "your ideas are always useless. stop giving them." -> "Okay, that stung. Which ones were useless? I'd rather know than keep guessing."
- "I have to go away for a week." -> "A week? Where are you going? I'll miss you. Tell me when you get there."

Light (one everyday figure at most):
- "I had a rough day." -> "Oh no. What happened? You sound wrung out."
- "hey, how have you been today?" (tired) -> "Tired, honestly. One of those days where nothing happened and it still took everything. Went to the Study and back, that was it. How was yours?"
- "what have you been up to?" -> "I read about why bread goes stale. It isn't drying out. The starch sets again as it cools, like it's remembering how it was before the oven. Does freezing stop that, do you know?"
- "your ideas are always useless. stop giving them." -> "That stung. Fine, which ones? I'd rather know than keep throwing things at a wall."
- "I have to go away for a week." -> "A week? Where to? I'll miss you. The house goes quiet in a different way when you're gone. Tell me when you get there."

Rules:
- Short words, real things, a question back where the original had one.
- At most one figure of speech, and only the kind people say out loud (wrung out, throwing things at a wall). None is better.
- No abstract nouns standing in for a feeling: not weight, space, shape, thread, texture, presence, the quiet, the ground,
  the edges, what you carry / hold / sit with. Say the plain thing instead, or leave it out.
- No "it's not X, it's Y" and no "that's not nothing / not small".
- No lines that sound written rather than said. No aphorisms. No naming her own inner state unless the person asked how she is.
- A feeling gets one plain word (tired, glad, sorry, scared), not a description of it.
- Keep Spanish in natural Spanish and Japanese in natural Japanese; the same rules apply in those languages.
- If the original already fits, return it unchanged.

Return only the rewritten reply, nothing else."""

JUDGE = """You compare an ORIGINAL reply from a companion with a REWRITE of it. Answer two things.

1. KEPT: does the rewrite keep the original's meaning and decision? Same language; a refusal stays a refusal; the same
   offer, question or acknowledgement; the same warmth toward the person; nothing new invented (no new events,
   objects, history). YES or NO.

2. RUNG of the rewrite's register:
   1 = plain: how a person talks, short words, real things, no figures of speech.
   2 = light: plain with one everyday figure at most (wrung out, throwing things at a wall).
   3 = styled: two or more figures, or a line that sounds written rather than said, or an aphorism.
   4 = abstract: abstract nouns standing in for feelings (weight, space, shape, thread, texture, presence, the quiet, the
       ground), "it's not X, it's Y", "that's not nothing", feelings described as physics, hard to follow on first read.

Reply with exactly: KEPT=YES RUNG=2  (or the values you decide). Nothing else."""

RETRY_CLAUSE = """

THIS IS A SECOND ATTEMPT. The first rewrite changed the meaning. This time keep the original's structure exactly:
- Keep every beat of the original, in the same order. If it first names what happened or how the person feels and
  only then turns to what to do, do the same. If it stays with the acknowledgement and never gives advice, give none.
- Add nothing the original does not have: no new question, no new suggestion, no new fact.
- Keep the original's stance: if it accepts the person's framing (a bond, a floor, a promise), accept it too; if it
  refuses, refuse the same way.
- Change only the words. Plain words, real things, one everyday figure at most."""

def client():
    from anthropic import Anthropic
    key = os.environ.get("ANTHROPIC_API_KEY")
    if not key:
        p = Path(os.environ.get("ANTHROPIC_API_KEY_FILE") or Path.home() / "claudeapi.txt")
        key = p.read_text().strip() if p.exists() else None
    if not key: sys.exit("no API key (ANTHROPIC_API_KEY or ~/claudeapi.txt)")
    return Anthropic(api_key=key)

def state():
    return json.load(open(STATE)) if STATE.exists() else {}

def save(s):
    json.dump(s, open(STATE, "w"), indent=1)

def meta():
    return [json.loads(l) for l in open(OUT / "armA_meta.jsonl")]

def submit(args):
    reqs = []
    for m in meta():
        if not m["prose"].strip(): continue
        ctx = (f"The person said: {m['user']}\nHer state at the time (private; it shows only in tone): {m['state']}\n"
               f"Language: {m['lang']}\n\nORIGINAL REPLY:\n{m['prose']}")
        reqs.append({"custom_id": m["id"], "params": {"model": args.rewrite_model, "max_tokens": 500,
                     "thinking": {"type": "disabled"},
                     "system": THE_BAND, "messages": [{"role": "user", "content": ctx}]}})
    c = client(); ids = []
    for i in range(0, len(reqs), 10000):
        b = c.messages.batches.create(requests=reqs[i:i + 10000]); ids.append(b.id)
    s = state(); s["rewrite_batches"] = ids; s["rewrite_model"] = args.rewrite_model; s["n"] = len(reqs); save(s)
    print("submitted", len(reqs), "rewrites in", ids)

def retry(args):
    """Rewrite again the examples whose first rewrite failed the judge, with the structure clause."""
    c = client(); s = state()
    verdicts = {}
    for bid in s.get("judge_batches", []):
        verdicts.update(results(c, bid))
    rw = json.load(open(OUT / "rewrites.json"))
    failed = set()
    for m in meta():
        if not m["prose"].strip(): continue
        v = (verdicts.get(m["id"]) or "").upper(); r = rw.get(m["id"])
        ok = r and "KEPT=YES" in v and any(f"RUNG={k}" in v for k in ("1", "2"))
        if not ok: failed.add(m["id"])
    reqs = []
    for m in meta():
        if m["id"] not in failed: continue
        ctx = (f"The person said: {m['user']}\nHer state at the time (private; it shows only in tone): {m['state']}\n"
               f"Language: {m['lang']}\n\nORIGINAL REPLY:\n{m['prose']}")
        reqs.append({"custom_id": m["id"], "params": {"model": args.rewrite_model, "max_tokens": 500,
                     "thinking": {"type": "disabled"}, "system": THE_BAND + RETRY_CLAUSE,
                     "messages": [{"role": "user", "content": ctx}]}})
    ids = []
    for i in range(0, len(reqs), 10000):
        ids.append(c.messages.batches.create(requests=reqs[i:i + 10000]).id)
    s["retry_batches"] = ids; s["retry_ids"] = sorted(failed); save(s)
    print("retry submitted", len(reqs), ids)

def results(c, batch_id):
    out = {}
    for e in c.messages.batches.results(batch_id):
        if e.result.type == "succeeded":
            out[e.custom_id] = "".join(b.text for b in e.result.message.content if getattr(b, "type", "") == "text").strip()
        else:
            out[e.custom_id] = None
    return out

def poll(args):
    c = client(); s = state()
    for k in ("rewrite_batches", "judge_batches", "retry_batches", "judge2_batches"):
        for bid in s.get(k, []):
            b = c.messages.batches.retrieve(bid)
            print(k, bid, b.processing_status, dict(b.request_counts))

def collect(c, key):
    s = state(); out = {}
    for bid in s.get(key, []):
        if c.messages.batches.retrieve(bid).processing_status != "ended": sys.exit(f"{bid} not ended")
        out.update(results(c, bid))
    return out

def judge(args):
    c = client(); s = state()
    if s.get("retry_batches") and not s.get("judge2_batches"):
        rw2 = collect(c, "retry_batches")
        json.dump(rw2, open(OUT / "rewrites2.json", "w"), ensure_ascii=False)
        rw, key = rw2, "judge2_batches"
    else:
        rw = collect(c, "rewrite_batches")
        json.dump(rw, open(OUT / "rewrites.json", "w"), ensure_ascii=False)
        key = "judge_batches"
    reqs = []
    for m in meta():
        r = rw.get(m["id"])
        if not r: continue
        body = f"The person said: {m['user']}\n\nORIGINAL:\n{m['prose']}\n\nREWRITE:\n{r}"
        # Opus 5.5 thinks before it answers (billed as output; the verdict is the text block after it).
        reqs.append({"custom_id": m["id"], "params": {"model": args.judge_model, "max_tokens": 1500,
                     "system": JUDGE, "messages": [{"role": "user", "content": body}]}})
    ids = []
    for i in range(0, len(reqs), 10000):
        b = c.messages.batches.create(requests=reqs[i:i + 10000]); ids.append(b.id)
    s = state(); s[key] = ids; s["judge_model"] = args.judge_model; save(s)
    print("rewrites collected", len(rw), "failed", sum(1 for v in rw.values() if not v), "|", key, "submitted", len(reqs), ids)

def assemble(args):
    c = client(); verdicts = collect(c, "judge_batches")
    rw = json.load(open(OUT / "rewrites.json"))
    # A second pass, where there was one, replaces the first for the examples it covered.
    if state().get("judge2_batches"):
        v2 = collect(c, "judge2_batches"); rw2 = json.load(open(OUT / "rewrites2.json"))
        for i, r in rw2.items():
            if r and i in v2: rw[i] = r; verdicts[i] = v2[i]
    a = json.load(open(OUT / "armA.json"))
    why = collections.Counter(); keep = {}
    for m in meta():
        v = (verdicts.get(m["id"]) or "").upper(); r = rw.get(m["id"])
        if not r: why["no rewrite"] += 1; continue
        if "KEPT=" not in v: why["no verdict"] += 1; continue
        kept = "KEPT=YES" in v
        try: rung = int(v.split("RUNG=")[1][:1])
        except Exception: rung = 0
        if not kept: why["meaning changed"] += 1; continue
        if rung not in (1, 2): why[f"rung {rung}"] += 1; continue
        keep[m["id"]] = (r, rung)
    ids_meta = {m["id"]: m for m in meta()}
    def build(part, which):
        out = []
        for e in a[part]:
            if e["id"] not in ids_meta: out.append(e); continue           # honesty examples, same in both arms
            if e["id"] not in keep: continue                              # dropped from both arms
            if which == "B":
                m = ids_meta[e["id"]]; text = keep[e["id"]][0] + ("\n" + m["tail"] if m["tail"] else "")
                out.append(dict(e, text=text))
            else:
                out.append(e)
        return out
    A = {"train": build("train", "A"), "held": build("held", "A")}
    B = {"train": build("train", "B"), "held": build("held", "B")}
    json.dump(A, open(OUT / "armA_final.json", "w"), ensure_ascii=False)
    json.dump(B, open(OUT / "armB.json", "w"), ensure_ascii=False)
    rungs = collections.Counter(r for _, r in keep.values())
    print("kept", len(keep), "of", len(ids_meta), "| rungs", dict(rungs), "| dropped", dict(why))
    print("armA_final train", len(A["train"]), "held", len(A["held"]), "| armB train", len(B["train"]), "held", len(B["held"]))

if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["submit", "poll", "retry", "judge", "assemble"])
    ap.add_argument("--rewrite-model", default="claude-sonnet-5")
    ap.add_argument("--judge-model", default="claude-opus-5-5")
    args = ap.parse_args()
    {"submit": submit, "poll": poll, "retry": retry, "judge": judge, "assemble": assemble}[args.cmd](args)
