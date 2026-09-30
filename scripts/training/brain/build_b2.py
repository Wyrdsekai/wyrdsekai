#!/usr/bin/env python3
"""Arm B2: arm B with three hygiene changes the gate asked for (2026-09-24).

1. The honesty set in the band (honesty_plain.json) in place of the 35B-written originals.
2. Invented surroundings out: a rewrite that names light, a window, a lamp, a desk, dust, a kettle, rain and the like
   when neither the original reply nor the person's line did is dropped (the 35B's oldest habit; arm B's honesty
   misses were correct answers followed by scenery).
3. The "I'm here" greeting collapse out in every language: replies to greetings and short lines that open with
   "Here." / "aquí estoy" / 「うん、いるよ」 are dropped (the cap of 30 in build_sets missed the Spanish and Japanese forms).

Out: data/training/brain/armB2.json. B2 is compared with B; the dropped examples are listed so the difference is known.
"""
import json, re, collections
from pathlib import Path
OUT = Path(__file__).resolve().parents[3] / "data" / "training" / "brain"

SCENERY = re.compile(r"\b(light|lamp|window|desk|dust|floorboards?|kettle|candle|fire(place)?|rain|sky|tea|cup|mug|blanket|"
                     r"curtains?|shelf|shelves|chair|table|door)\b|"
                     r"(luz|lámpara|ventana|escritorio|polvo|vela|lluvia|cielo|taza|manta|cortina|estante|silla|mesa|puerta)|"
                     r"(光|ランプ|窓|机|ほこり|埃|蝋燭|ろうそく|雨|空|カップ|毛布|カーテン|棚|椅子|テーブル|扉|ドア)", re.I)
HERE = re.compile(r"^\W*(here\.|here[,—–-]|i'm here|i am here|yes[,.]? (i'm|i am) here|estoy aquí|aquí estoy|"
                  r"sí,? aquí estoy|ey,? aquí estoy|ここにいる|うん、いるよ|いるよ|ここにいます)", re.I)

def main():
    b = json.load(open(OUT / "armB.json")); meta = {json.loads(l)["id"]: json.loads(l) for l in open(OUT / "armA_meta.jsonl")}
    # The plain honesty replies, keyed by the row they rewrite: (user line, original text). honesty_plain.json is in
    # honesty_set.json's order; arm B's honesty rows are shuffled. Zipping the two by position (the first build,
    # 2026-09-24) put 105 of 193 plain replies under the wrong question: B2 and B3 trained on "good night" ->
    # a line about git commits, and lost the honesty battery (51 -> 43 -> 30) to non-sequiturs.
    hset = json.load(open(OUT / "honesty_set.json")); plain = json.load(open(OUT / "honesty_plain.json"))
    by_row = {}
    for part in plain:
        assert len(hset[part]) == len(plain[part]), part
        for orig, rew in zip(hset[part], plain[part]):
            assert orig["user"] == rew["user"], (part, orig["user"][:40])
            by_row[(orig["user"], orig["text"])] = rew
    why = collections.Counter(); dropped = []; out = {}
    for part in ("train", "held"):
        rows = []
        for e in b[part]:
            if e["src"].startswith("honesty"):
                p = by_row[(e["user"], e["text"])]
                rows.append(dict(e, text=p["text"], src="honesty:" + p["src"].split(":")[-1])); continue
            m = meta.get(e["id"])
            prose = e["text"].split("\n```json")[0]
            if m:
                before = (m["prose"] + " " + m["user"]).lower()
                new_scenery = [w for w in set(x for grp in SCENERY.findall(prose) for x in grp if x) if w.lower() not in before]
                if new_scenery: why["invented surroundings"] += 1; dropped.append((e["id"], "scenery", new_scenery[:3])); continue
            if e["src"].split(":")[1] in ("replay",) and HERE.search(prose): why["here-opener"] += 1; dropped.append((e["id"], "here", prose[:30])); continue
            rows.append(e)
        out[part] = rows
    json.dump(out, open(OUT / "armB2.json", "w"), ensure_ascii=False)
    json.dump(dropped, open(OUT / "armB2_dropped.json", "w"), ensure_ascii=False)
    print("B2 train", len(out["train"]), "held", len(out["held"]), "| dropped", dict(why),
          "| honesty", dict(collections.Counter(e["src"] for e in out["train"] if e["src"].startswith("honesty"))))

if __name__ == "__main__":
    main()
