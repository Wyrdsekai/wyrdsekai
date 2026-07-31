#!/usr/bin/env python3
"""
Granite-Substrate v2 corpus prep · merge V5 substrate + L4 welfare into
one raw file with proper _meta (action, lang) for stratified splitting.

V5 substrate_raw.jsonl already has _meta.action + _meta.lang.
L4 substrate_welfare_v2_raw.jsonl has _meta.batch_cid (l4_<lang>_NN) — we
extract lang from the cid and action from the assistant's JSON action block.

Writes: data/training/substrate/granite_substrate_v2_raw.jsonl
"""

import json
import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
V5 = REPO / "data/training/substrate/substrate_raw.jsonl"
L4 = REPO / "data/training/substrate/substrate_welfare_v2_raw.jsonl"
OUT = REPO / "data/training/substrate/granite_substrate_v2_raw.jsonl"


def load(p: Path):
    return [json.loads(line) for line in p.read_text().splitlines() if line.strip()]


def extract_action(asst: str) -> str:
    m = re.search(r'```json\s*({.*?})\s*```', asst, re.S)
    if not m:
        return "?"
    try:
        return json.loads(m.group(1)).get("action", "?")
    except json.JSONDecodeError:
        return "?"


def lang_from_cid(cid: str) -> str:
    m = re.match(r"l4_([a-z]{2})_\d+", cid)
    return m.group(1) if m else "?"


def main():
    v5 = load(V5)
    l4 = load(L4)
    print(f"V5: {len(v5)} examples")
    print(f"L4: {len(l4)} examples")

    enriched = []
    for ex in l4:
        meta = ex.get("_meta", {})
        cid = meta.get("batch_cid", "")
        action = extract_action(ex["messages"][-1]["content"])
        lang = lang_from_cid(cid)
        ex["_meta"] = {"action": action, "lang": lang, "source": "l4_welfare_v2"}
        enriched.append(ex)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    with OUT.open("w") as f:
        for ex in v5 + enriched:
            f.write(json.dumps(ex, ensure_ascii=False) + "\n")

    # Summary breakdown
    from collections import defaultdict
    cells = defaultdict(int)
    for ex in v5 + enriched:
        m = ex.get("_meta", {})
        cells[(m.get("action", "?"), m.get("lang", "?"))] += 1
    print(f"\nMerged {len(v5) + len(enriched)} → {OUT}")
    print("\nPer-(action, lang) cells:")
    actions = sorted({k[0] for k in cells})
    langs = sorted({k[1] for k in cells})
    for action in actions:
        row = f"  {action:<35} | "
        total = 0
        for lang in langs:
            n = cells.get((action, lang), 0)
            row += f"{n:>4} "
            total += n
        row += f" | {total}"
        print(row)


if __name__ == "__main__":
    main()
