#!/usr/bin/env python3
"""
Classifier observability dashboard — reads the per-agent event log + lineage
and produces a snapshot of classifier activity.

Works purely against on-disk artifacts. No server query, no actor poll.
Safe to run while Wyrd is live.

Usage:
    scripts/classifier/stats.py                         # all agents
    scripts/classifier/stats.py --did companion-wyrd    # one agent
    scripts/classifier/stats.py --since 24h             # rolling window
    scripts/classifier/stats.py --tail                  # live events
    scripts/classifier/stats.py --json                  # machine-readable
"""
import argparse
import json
import os
import re
import sys
import time
from pathlib import Path
from datetime import datetime, timedelta, timezone

CLASSIFIER_DIR = Path.home() / ".wyrdsekai" / "classifiers"


def parse_since(s: str) -> datetime:
    """Parse '24h', '30m', '7d' into a cutoff datetime."""
    if not s: return None
    m = re.match(r"(\d+)([hmd])", s.lower())
    if not m: return None
    n = int(m.group(1))
    unit = m.group(2)
    delta = {"h": timedelta(hours=n), "m": timedelta(minutes=n), "d": timedelta(days=n)}[unit]
    return datetime.now(timezone.utc) - delta


def read_events(path: Path, since: datetime = None):
    events = []
    outcomes = {}
    if not path.exists(): return events, outcomes
    for line in path.read_text().splitlines():
        if not line.strip(): continue
        try:
            node = json.loads(line)
        except: continue
        typ = node.get("type", "event")
        ts_str = node.get("ts", "")
        try:
            ts = datetime.fromisoformat(ts_str.replace("Z", "+00:00"))
        except: ts = None
        if since and ts and ts < since: continue
        if typ == "outcome":
            outcomes[node.get("id", "")] = node.get("outcome", "UNKNOWN")
            continue
        events.append({
            "id": node.get("id", ""),
            "ts": ts,
            "head": node.get("head", ""),
            "text": node.get("text", ""),
            "label": node.get("label", ""),
            "confidence": float(node.get("confidence", 0.0)),
            "source": node.get("source", "L1"),
            "outcome": node.get("outcome", "UNKNOWN"),
        })
    for e in events:
        if e["id"] in outcomes:
            e["outcome"] = outcomes[e["id"]]
    return events, outcomes


def read_lineage(path: Path):
    if not path.exists(): return []
    entries = []
    for line in path.read_text().splitlines():
        if not line.strip(): continue
        try: entries.append(json.loads(line))
        except: continue
    return entries


def conf_bucket(c: float) -> str:
    if c < 0.50: return "<0.50"
    if c < 0.65: return "0.50-0.65"
    if c < 0.75: return "0.65-0.75"
    if c < 0.85: return "0.75-0.85"
    return "≥0.85"


def report_agent(agent_dir: Path, since: datetime):
    events_path = agent_dir / "events.jsonl"
    events, outcomes = read_events(events_path, since)
    did = agent_dir.name

    # Also scan rotated logs
    rotated_events = []
    for p in agent_dir.glob("events.consumed-*.jsonl"):
        rot_evts, rot_outs = read_events(p, since)
        rotated_events.extend(rot_evts)
    events = events + rotated_events

    if not events:
        return {"did": did, "empty": True}

    by_head = {}
    for e in events:
        by_head.setdefault(e["head"], []).append(e)

    heads_out = {}
    for head, evts in by_head.items():
        lbl_count = {}
        conf_hist = {"<0.50": 0, "0.50-0.65": 0, "0.65-0.75": 0, "0.75-0.85": 0, "≥0.85": 0}
        outcome_count = {"POSITIVE": 0, "NEGATIVE": 0, "UNKNOWN": 0}
        dispatch = 0
        for e in evts:
            lbl_count[e["label"]] = lbl_count.get(e["label"], 0) + 1
            conf_hist[conf_bucket(e["confidence"])] += 1
            outcome_count[e["outcome"]] = outcome_count.get(e["outcome"], 0) + 1
            # Dispatch heuristic: head=REQUEST_TYPE, label=delegate, conf≥0.75
            if head == "REQUEST_TYPE" and e["label"] == "delegate" and e["confidence"] >= 0.75:
                dispatch += 1

        heads_out[head] = {
            "total": len(evts),
            "labels": dict(sorted(lbl_count.items(), key=lambda kv: -kv[1])),
            "confidence_histogram": conf_hist,
            "outcomes": outcome_count,
            "auto_dispatch_candidates": dispatch,
        }

    lineage_entries = []
    for p in agent_dir.glob("*.lineage.jsonl"):
        lineage_entries.extend(read_lineage(p))

    return {
        "did": did,
        "heads": heads_out,
        "lineage_entries": len(lineage_entries),
        "last_forge": lineage_entries[-1] if lineage_entries else None,
        "empty": False,
    }


def print_text(reports):
    if not reports:
        print("No classifier events found yet.")
        print("Location checked: " + str(CLASSIFIER_DIR))
        return

    for rep in reports:
        if rep["empty"]:
            print(f"\n━━━ {rep['did']} ━━━")
            print("  (no events in window)")
            continue
        print(f"\n━━━ {rep['did']} ━━━")
        for head, h in rep["heads"].items():
            print(f"\n  [{head}]  {h['total']} events")
            print(f"    Labels:")
            for lbl, c in h["labels"].items():
                pct = 100 * c / h["total"]
                bar = "█" * int(pct / 2)
                print(f"      {lbl:15s}  {c:5d} ({pct:4.1f}%)  {bar}")
            print(f"    Confidence distribution:")
            for bucket in ["<0.50", "0.50-0.65", "0.65-0.75", "0.75-0.85", "≥0.85"]:
                c = h["confidence_histogram"][bucket]
                pct = 100 * c / h["total"]
                bar = "█" * int(pct / 2)
                print(f"      {bucket:11s}  {c:5d} ({pct:4.1f}%)  {bar}")
            print(f"    Outcomes: "
                  f"POSITIVE={h['outcomes']['POSITIVE']} "
                  f"NEGATIVE={h['outcomes']['NEGATIVE']} "
                  f"UNKNOWN={h['outcomes']['UNKNOWN']}")
            print(f"    Auto-dispatch candidates (delegate ≥0.75): "
                  f"{h['auto_dispatch_candidates']}")

        if rep.get("last_forge"):
            lf = rep["last_forge"]
            print(f"\n  Lineage: {rep['lineage_entries']} Forge entries. "
                  f"Most recent:")
            print(f"    ts={lf.get('ts','?')}")
            print(f"    corpus_size={lf.get('corpus_size','?')} "
                  f"pseudo_labels_added={lf.get('pseudo_labels_added','?')} "
                  f"retrain={'ok' if lf.get('retrain_succeeded') else 'skipped/failed'}")
            if lf.get("prior_accuracy") is not None:
                print(f"    prior_accuracy={lf.get('prior_accuracy'):.4f} "
                      f"new_accuracy={lf.get('new_accuracy', -1):.4f}")


def tail_events(agent_dir: Path):
    path = agent_dir / "events.jsonl"
    if not path.exists():
        print(f"No events log at {path} (yet)", file=sys.stderr)
        return
    size = path.stat().st_size
    print(f"Tailing {path}", file=sys.stderr)
    while True:
        time.sleep(1)
        try:
            new_size = path.stat().st_size
        except FileNotFoundError:
            time.sleep(1); continue
        if new_size <= size: continue
        with path.open() as f:
            f.seek(size)
            for line in f:
                try:
                    node = json.loads(line)
                    typ = node.get("type", "event")
                    if typ == "outcome":
                        print(f"  [OUTCOME] id={node.get('id','')[:8]} "
                              f"{node.get('outcome','?')} ({node.get('note','')})")
                    else:
                        text = node.get("text", "")[:60].replace("\n", " ")
                        print(f"  [{node.get('head','?'):13s}] "
                              f"{node.get('label','?'):12s} "
                              f"conf={node.get('confidence',0):.3f}  "
                              f"{text}")
                except: pass
        size = new_size


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--did", help="Filter to specific agent directory")
    ap.add_argument("--since", help="Window, e.g. 24h, 30m, 7d")
    ap.add_argument("--tail", action="store_true", help="Live tail events")
    ap.add_argument("--json", action="store_true", help="Machine-readable output")
    args = ap.parse_args()

    since = parse_since(args.since) if args.since else None

    if args.tail:
        if not args.did:
            print("--tail requires --did", file=sys.stderr); sys.exit(2)
        tail_events(CLASSIFIER_DIR / args.did)
        return

    if not CLASSIFIER_DIR.exists():
        print(f"Classifier dir not found: {CLASSIFIER_DIR}", file=sys.stderr)
        sys.exit(1)

    agent_dirs = sorted([d for d in CLASSIFIER_DIR.iterdir() if d.is_dir()])
    if args.did:
        agent_dirs = [d for d in agent_dirs if d.name == args.did]

    # Skip test-only dirs unless explicitly requested
    if not args.did:
        agent_dirs = [d for d in agent_dirs if not d.name.startswith("did_test")]

    reports = [report_agent(d, since) for d in agent_dirs]

    if args.json:
        # datetime isn't JSON-native, strip
        def clean(r):
            if r.get("last_forge"):
                r["last_forge"] = {k: v for k, v in r["last_forge"].items()}
            return r
        print(json.dumps([clean(r) for r in reports], indent=2, default=str))
    else:
        print_text(reports)


if __name__ == "__main__":
    main()
