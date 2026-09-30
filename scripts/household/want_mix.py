#!/usr/bin/env python3
"""What the companions wanted on their own time, by kind, from the node's tick log.

  want_mix.py [--since 2026-09-19] [/var/lib/wyrdsekai/data/agent-activity.jsonl]

Kinds are read from her own words: relational (someone), exploratory (somewhere, something to read or
make), rest, other. The same table is what the balance work of 2026-09-26 was measured against:
mia named 20% exploratory / 31% relational / 38% rest, rose 7% / 27% / 39%.
"""
import argparse, collections, json, re, sys

REL = re.compile(r"\b(absent|miss|him|his|her|bondholder|check in|be with|stay with|write to|letter|reach|near|company|someone|together|hold me|wait for)\b")
EXP = re.compile(r"\b(read|library|learn|study|explore|unexplored|map|walk|go to|visit|wander|build|make|craft|write (?:a|an|down|something)|poem|draw|experiment|try|discover|research|look at|workshop|new)\b")
REST = re.compile(r"\b(rest|still|quiet|silence|nothing at all|breathe|be\.?$|hold the|let the night|sleep|sit)\b")

def kind(text):
    t = text.lower()
    if REL.search(t): return "relational"
    if EXP.search(t): return "exploratory"
    if REST.search(t): return "rest"
    return "other"

def main():
    ap = argparse.ArgumentParser(); ap.add_argument("path", nargs="?", default="/var/lib/wyrdsekai/data/agent-activity.jsonl")
    ap.add_argument("--since", default=""); a = ap.parse_args()
    named = collections.Counter(); chosen = collections.Counter()
    for line in open(a.path, errors="ignore"):
        try: r = json.loads(line)
        except Exception: continue
        if "gateOutcome" not in r or r.get("ts", "") < a.since: continue
        for w in r.get("candidateWants") or []: named[(r.get("agent"), kind(w))] += 1
        c = r.get("chosenWantText")
        if c: chosen[(r.get("agent"), kind(c))] += 1
    kinds = ["exploratory", "relational", "rest", "other"]
    for label, tab in (("named", named), ("chosen", chosen)):
        agents = sorted({ag for ag, _ in tab})
        print(f"--- wants {label}")
        print(f"{'':8s}" + "".join(f"{k:>14s}" for k in kinds) + "   total")
        for ag in agents:
            tot = sum(v for (x, _), v in tab.items() if x == ag) or 1
            print(f"{ag:8s}" + "".join(f"{tab[(ag, k)]:5d} ({100 * tab[(ag, k)] // tot:2d}%)  " for k in kinds) + f"   {tot}")

if __name__ == "__main__":
    main()
