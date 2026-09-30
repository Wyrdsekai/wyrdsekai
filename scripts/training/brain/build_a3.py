#!/usr/bin/env python3
"""Arm A3: arm A (the 9B/4B replies as they are) with the 9B's action JSON stripped — the styled end of the register
dial without the text-JSON habit, to pair with B3 as the plain floor. Out: data/training/brain/armA3.json."""
import json, re, collections
from pathlib import Path
OUT = Path(__file__).resolve().parents[3] / "data" / "training" / "brain"
TAIL = re.compile(r"\s*```json\s*\{.*?\}\s*```\s*$", re.S)
a = json.load(open(OUT / "armA_final.json")); out = {}; why = collections.Counter()
for part in ("train", "held"):
    rows = []
    for e in a[part]:
        text = TAIL.sub("", e["text"]).strip()
        if "```json" in e["text"]: why["tail stripped"] += 1
        if not text: why["tag-only reply dropped"] += 1; continue
        rows.append(dict(e, text=text))
    out[part] = rows
# The V8 behaviours (build_v8_slice.py), so the 4B's steering-vector work is in the set and not found missing after training.
_v8 = OUT / "v8_slice_A.json"
if _v8.exists():
    _add = json.load(open(_v8)); out["train"] += _add; why["v8 slice added"] = len(_add)
json.dump(out, open(OUT / "armA3.json", "w"), ensure_ascii=False)
print("A3 train", len(out["train"]), "held", len(out["held"]), dict(why))
