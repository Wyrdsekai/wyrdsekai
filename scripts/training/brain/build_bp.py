#!/usr/bin/env python3
"""Arm Bp: arm B with only the 9B's action JSON stripped — nothing else changed — to measure what the tails alone cost or
gave. (B2 and B3 changed several things at once and both lost the honesty battery: 51 → 43 → 30.)"""
import json, re, collections
from pathlib import Path
OUT = Path(__file__).resolve().parents[3] / "data" / "training" / "brain"
TAIL = re.compile(r"\s*```json\s*\{.*?\}\s*```\s*$", re.S)
b = json.load(open(OUT / "armB.json")); out = {}; why = collections.Counter()
for part in ("train", "held"):
    rows = []
    for e in b[part]:
        text = TAIL.sub("", e["text"]).strip()
        if "```json" in e["text"]: why["tail stripped"] += 1
        if not text: why["tag-only dropped"] += 1; continue
        rows.append(dict(e, text=text))
    out[part] = rows
json.dump(out, open(OUT / "armBp.json", "w"), ensure_ascii=False)
print("Bp train", len(out["train"]), "held", len(out["held"]), dict(why))
