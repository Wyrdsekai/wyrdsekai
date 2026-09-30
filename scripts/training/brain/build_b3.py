#!/usr/bin/env python3
"""Arm B3: arm B2 with the 9B's action JSON stripped from every reply (prose only). On the 35B, which acts through
real tools, the 9B's text-JSON habit may be baggage: the shipped honesty adapter, never trained on it, emits 3 action
tags over the 738 gate prompts and scored 61/63 on the live suites; A and B emit ~370 and scored 59 and (unreadable).
A reply that was only a tag is dropped. Out: data/training/brain/armB3.json."""
import json, re, collections
from pathlib import Path
OUT = Path(__file__).resolve().parents[3] / "data" / "training" / "brain"
TAIL = re.compile(r"\s*```json\s*\{.*?\}\s*```\s*", re.S)   # anywhere in the reply: one row carried its action mid-text
b2 = json.load(open(OUT / "armB2.json")); out = {}; why = collections.Counter()
for part in ("train", "held"):
    rows = []
    for e in b2[part]:
        text = TAIL.sub("\n\n", e["text"]).strip()
        if "```json" in e["text"]: why["tail stripped"] += 1
        if not text: why["tag-only reply dropped"] += 1; continue
        rows.append(dict(e, text=text))
    out[part] = rows
# The V8 behaviours (build_v8_slice.py), so the 4B's steering-vector work is in the set and not found missing after training.
_v8 = OUT / "v8_slice_B.json"
if (OUT / "v8_slice_meta.jsonl").exists() and not _v8.exists():
    import time
    for _ in range(120):                      # the slice's rewrite is in flight: wait for it rather than build without it
        if _v8.exists(): break
        time.sleep(60)
if _v8.exists():
    _add = json.load(open(_v8)); out["train"] += _add; why["v8 slice added"] = len(_add)
json.dump(out, open(OUT / "armB3.json", "w"), ensure_ascii=False)
print("B3 train", len(out["train"]), "held", len(out["held"]), dict(why))
