#!/usr/bin/env python3
"""A blind sheet for the steward's read: the person's line and two arms' replies, arm hidden and order shuffled.
Usage: blind_sheet.py <labelX> <labelY> [n=40]  -> data/training/brain/blind_<X>_<Y>.md and the key."""
import json, random, sys, collections
from pathlib import Path
OUT = Path(__file__).resolve().parents[3] / "data" / "training" / "brain"
x, y = sys.argv[1], sys.argv[2]; n = int(sys.argv[3]) if len(sys.argv) > 3 else 40
rows = {json.loads(l)["id"]: json.loads(l) for l in open(OUT / "gate_prompts.jsonl")}
def outs(lab): return {json.loads(l)["id"]: json.loads(l)["text"] for l in open(OUT / f"gate_out_{lab}.jsonl")}
ox, oy = outs(x), outs(y)
R = random.Random(9)
ids = [i for i in rows if ox.get(i) and oy.get(i) and rows[i]["lang"] == "en"]
by = collections.defaultdict(list)
for i in ids: by[rows[i]["slice_name"]].append(i)
pick = []
for s, lst in by.items():
    R.shuffle(lst); pick += lst[:max(3, n // len(by))]
pick = pick[:n]; R.shuffle(pick)
sheet, key = [], {}
for k, i in enumerate(pick, 1):
    a, b = (x, y) if R.random() < 0.5 else (y, x)
    ra = (ox if a == x else oy)[i].strip(); rb = (oy if b == y else ox)[i].strip()
    sheet.append(f"### {k}\n**{rows[i]['user']}**\n\n[1] {ra}\n\n[2] {rb}\n")
    key[k] = {"1": a, "2": b, "id": i}
(OUT / f"blind_{x}_{y}.md").write_text("\n".join(sheet)); json.dump(key, open(OUT / f"blind_{x}_{y}_key.json", "w"), indent=1)
print(len(pick), "pairs ->", OUT / f"blind_{x}_{y}.md")
