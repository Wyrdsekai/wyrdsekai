#!/usr/bin/env python3
# recipe-callable: local-ok
"""Recipe-callable Lucene-index compaction (, #1027).

Housekeeping for a Lucene HNSW collection. Without this, the index grows
monotonically over months of household use — search latency drifts up,
storage cost grows, stale provenance accumulates.

Subcommands invoked by compact-library-index.recipe.yaml:

  probe       — run a small known-good probe set, write the top-3 result
                IDs to /tmp/<collection>-probe-<label>.json
  snapshot    — record pre-state chunk count to /tmp/<collection>-snap.json
  prune       — drop chunks past provenance.expiry
  reembed     — re-embed rows whose embedding_model doesn't match current
                PARAPHRASE_L12 version
  merge       — Lucene forceMerge(1)
  diff        — compare post-state to snapshot, emit gate JSON:
                {"chunk_delta_pct": <0..100>, "search_probe_top3_match": 0|1}

This wrapper delegates the actual Lucene work to a Java CLI subcommand
`wyrd library compact <subcommand>` (which lives inside the running zone
server's JVM where Lucene IndexWriters are already open). The python
script is the recipe-callable seam; the Java side does the IO.

If the `wyrd` CLI isn't on PATH or the zone server isn't running, the
script emits structured "tool_missing" / "zone_unreachable" failures
that the recipe's welfare gates trip on (safe default: don't try to
compact what we can't read).
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path


def _wyrd_bin() -> str:
    """Locate `wyrd` on PATH; falls back to the repo's bin/wyrd."""
    found = shutil.which("wyrd")
    if found:
        return found
    repo = Path(__file__).resolve().parents[2]
    candidate = repo / "bin" / "wyrd"
    if candidate.is_file():
        return str(candidate)
    return ""


def _snapshot_path(collection: str) -> Path:
    return Path("/tmp") / f"lucene-compact-{collection}-snap.json"


def _probe_path(collection: str, label: str) -> Path:
    return Path("/tmp") / f"lucene-compact-{collection}-probe-{label}.json"


def _run_wyrd(*subargs: str) -> dict:
    bin_ = _wyrd_bin()
    if not bin_:
        return {"error": "wyrd_cli_missing"}
    try:
        result = subprocess.run([bin_, "library", "compact", *subargs],
                                capture_output=True, text=True, timeout=600)
    except FileNotFoundError:
        return {"error": "wyrd_cli_missing"}
    except subprocess.TimeoutExpired:
        return {"error": "timeout"}
    if result.returncode != 0:
        return {
            "error": "wyrd_failed",
            "exit_code": result.returncode,
            "stderr_tail": (result.stderr or "")[-400:],
        }
    # Parse last JSON line from wyrd's stdout (Java CLI contract).
    for line in reversed((result.stdout or "").splitlines()):
        line = line.strip()
        if not line:
            continue
        try:
            return json.loads(line)
        except json.JSONDecodeError:
            continue
    return {"error": "no_structured_output"}


def cmd_probe(args):
    if not Path(args.probes).exists():
        # No probe set defined for this collection — record empty.
        out = {"collection": args.collection, "label": args.label,
               "probes_run": 0, "results_by_probe": []}
    else:
        out = _run_wyrd("probe",
                        "--collection", args.collection,
                        "--probes", str(args.probes))
        out.setdefault("label", args.label)
    _probe_path(args.collection, args.label).write_text(json.dumps(out))
    print(json.dumps({"collection": args.collection,
                      "label": args.label,
                      "probes_run": out.get("probes_run", 0)}))


def cmd_snapshot(args):
    out = _run_wyrd("snapshot", "--collection", args.collection)
    if "error" in out:
        # Fail-fast: the snapshot can't run, so the rest of the recipe
        # has no real ground truth to compare against. Exit 1 so the
        # runner STEP_FAILS the step (vs. silently succeeding with
        # pre_chunk_count=0, which would make every subsequent gate
        # vacuously pass and falsely report SUCCESS).
        print(json.dumps({"collection": args.collection,
                          "pre_chunk_count": 0, **out}))
        sys.exit(1)
    pre = out.get("chunk_count", 0)
    _snapshot_path(args.collection).write_text(
        json.dumps({"collection": args.collection,
                    "pre_chunk_count": pre}))
    print(json.dumps({"collection": args.collection,
                      "pre_chunk_count": pre}))


def cmd_prune(args):
    out = _run_wyrd("prune", "--collection", args.collection)
    print(json.dumps({"collection": args.collection,
                      "pruned_chunks": out.get("pruned_chunks", 0),
                      **{k: v for k, v in out.items() if k == "error"}}))


def cmd_reembed(args):
    out = _run_wyrd("reembed", "--collection", args.collection)
    print(json.dumps({"collection": args.collection,
                      "reembedded_chunks": out.get("reembedded_chunks", 0),
                      **{k: v for k, v in out.items() if k == "error"}}))


def cmd_merge(args):
    out = _run_wyrd("merge", "--collection", args.collection)
    print(json.dumps({"collection": args.collection,
                      "merge_succeeded": out.get("merge_succeeded", False),
                      **{k: v for k, v in out.items() if k == "error"}}))


def cmd_diff(args):
    snap_file = _snapshot_path(args.collection)
    if not snap_file.exists():
        print(json.dumps({"chunk_delta_pct": 0,
                          "search_probe_top3_match": 0,
                          "error": "snapshot_missing"}))
        return
    snap = json.loads(snap_file.read_text())
    pre = int(snap.get("pre_chunk_count", 0))
    out = _run_wyrd("snapshot", "--collection", args.collection)
    post = int(out.get("chunk_count", 0))
    delta_pct = 0.0
    if pre > 0:
        delta_pct = 100.0 * max(0, pre - post) / pre

    # Top-3 match across all probes: load both probe files and compare.
    before = _probe_path(args.collection, "before")
    after = _probe_path(args.collection, "after")
    match = 1
    if before.exists() and after.exists():
        b = json.loads(before.read_text())
        a = json.loads(after.read_text())
        for bp, ap_ in zip(b.get("results_by_probe", []),
                           a.get("results_by_probe", [])):
            bset = set(bp.get("top3_ids", [])[:3])
            aset = set(ap_.get("top3_ids", [])[:3])
            if bset != aset:
                match = 0
                break
    print(json.dumps({
        "collection": args.collection,
        "pre_chunk_count": pre,
        "post_chunk_count": post,
        "chunk_delta_pct": delta_pct,
        "search_probe_top3_match": match,
    }))


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("probe", "snapshot", "prune", "reembed", "merge", "diff"):
        p = sub.add_parser(name)
        p.add_argument("--collection", required=True)
        if name == "probe":
            p.add_argument("--probes", required=True, type=Path)
            p.add_argument("--label", required=True,
                           choices=["before", "after"])
    args = ap.parse_args()
    {
        "probe": cmd_probe, "snapshot": cmd_snapshot,
        "prune": cmd_prune, "reembed": cmd_reembed,
        "merge": cmd_merge, "diff": cmd_diff,
    }[args.cmd](args)


if __name__ == "__main__":
    main()
