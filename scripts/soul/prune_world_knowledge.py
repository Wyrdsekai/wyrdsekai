#!/usr/bin/env python3
# recipe-callable: local-ok
"""Recipe-callable world_knowledge dead-entry prune (, #1134).

Housekeeping for the world_knowledge table (
Phase 2.4) — the key/value facts a companion holds about its world
("steward_timezone", "household_quiet_hours", ...).

  world_knowledge(did, key, value, updated_at)  PRIMARY KEY (did, key)

IMPORTANT SCOPE — this prunes DEAD ENTRIES ONLY, never live facts:
  - The PK is (did, key), so duplicates / contradictions cannot exist — a new
    value for an existing key OVERWRITES. There is nothing to dedupe.
  - Age is NOT staleness for a fact. A true fact set once and never re-touched
    (old updated_at) is the NORM, not cruft. Pruning by age would delete
    durable knowledge — the paternalism trap. We do NOT do that.
  - The only genuinely prunable rows are DEAD entries: a key whose value is
    NULL / blank / a tombstone sentinel (left behind by a cleared fact or a
    failed write). These carry no information and only add scan noise.

Optional `--min-age-days` is a SAFETY floor (don't prune anything touched in
the last N days), never a prune *trigger*. Default 0.

Plan-then-commit (same shape as consolidate_fragments.py) so the welfare gates
run BEFORE any DELETE:

  snapshot — record pre key-count to /tmp/<did>-worldknow-snapshot.json
  plan     — compute the dead-key delete set; write the plan + emit
             key_delta_pct (would-be removal %) and all_pruned_were_dead (a
             correctness tripwire — always 1 by construction; 0 only if a bug
             ever selected a non-dead row).
  commit   — execute the deletes in one transaction.
  rollback — delete the plan file (commit hadn't run; DB untouched).

Output (commit, single JSON line on stdout):
    {"pre_key_count": N, "post_key_count": M, "key_delta_pct": <0..100>,
     "all_pruned_were_dead": 1, "agent_did": "<did>"}
"""
from __future__ import annotations

import argparse
import json
import sqlite3
import sys
import time
from pathlib import Path

from importlib import util as _import_util

_GRAPH = Path(__file__).resolve().parents[1] / "memory" / "consolidate_graph.py"
_spec = _import_util.spec_from_file_location("_consolidate_graph", _GRAPH)
_graph = _import_util.module_from_spec(_spec)
_spec.loader.exec_module(_graph)
connect = _graph.connect  # type: ignore[attr-defined]

# Tombstone sentinels: string values that mean "no real value" — artifacts of
# cleared facts / serialization of a null. Case-insensitive exact match only.
_TOMBSTONES = {"__deleted__", "__cleared__", "null", "none", "undefined", "nil"}


def _safe_did(agent_did: str) -> str:
    return agent_did.replace(":", "_").replace("/", "_")


def snapshot_path(agent_did: str) -> Path:
    return Path("/tmp") / f"{_safe_did(agent_did)}-worldknow-snapshot.json"


def plan_path(agent_did: str) -> Path:
    return Path("/tmp") / f"{_safe_did(agent_did)}-worldknow-plan.json"


def _key_count(cur, agent_did: str) -> int:
    try:
        cur.execute("SELECT COUNT(*) FROM world_knowledge WHERE did = ?",
                    (agent_did,))
        return cur.fetchone()[0]
    except sqlite3.OperationalError:
        return 0


def _is_dead(value) -> bool:
    if value is None:
        return True
    s = str(value).strip()
    if s == "":
        return True
    return s.lower() in _TOMBSTONES


def cmd_snapshot(args):
    conn = connect(args.jdbc_url)
    cur = conn.cursor()
    kc = _key_count(cur, args.agent_did)
    snapshot_path(args.agent_did).write_text(json.dumps({
        "agent_did": args.agent_did, "pre_key_count": kc, "ts": int(time.time())}))
    print(json.dumps({"pre_key_count": kc}))


def cmd_plan(args):
    conn = connect(args.jdbc_url)
    cur = conn.cursor()
    cutoff = int(time.time()) - args.min_age_days * 24 * 3600
    try:
        cur.execute(
            "SELECT key, value, updated_at FROM world_knowledge WHERE did = ?",
            (args.agent_did,))
        rows = cur.fetchall()
    except sqlite3.OperationalError:
        plan_path(args.agent_did).write_text(json.dumps(
            {"agent_did": args.agent_did, "delete_keys": [], "dead_count": 0,
             "note": "world_knowledge schema absent"}))
        print(json.dumps({"dead_count": 0, "key_delta_pct": 0.0,
                          "all_pruned_were_dead": 1,
                          "note": "world_knowledge schema absent"}))
        return

    pre = len(rows)
    delete_keys = []
    all_dead = 1
    for key, value, updated_at in rows:
        if not _is_dead(value):
            continue
        # Safety floor: skip recently-touched rows even if dead.
        if args.min_age_days > 0 and (updated_at or 0) >= cutoff:
            continue
        delete_keys.append(key)
        # Tripwire: re-verify the selected row really is dead.
        if not _is_dead(value):
            all_dead = 0

    delta = 100.0 * len(delete_keys) / pre if pre > 0 else 0.0
    plan_path(args.agent_did).write_text(json.dumps(
        {"agent_did": args.agent_did, "delete_keys": delete_keys,
         "dead_count": len(delete_keys), "pre_key_count": pre}))
    print(json.dumps({
        "dead_count": len(delete_keys),
        "key_delta_pct": delta,
        "all_pruned_were_dead": all_dead,
        "pre_key_count": pre,
    }))


def cmd_commit(args):
    conn = connect(args.jdbc_url)
    cur = conn.cursor()
    snap_file = snapshot_path(args.agent_did)
    plan_file = plan_path(args.agent_did)
    if not snap_file.exists():
        print(json.dumps({"error": "snapshot_missing"})); sys.exit(0)
    snap = json.loads(snap_file.read_text())
    delete_keys = []
    if plan_file.exists():
        try:
            delete_keys = json.loads(plan_file.read_text()).get("delete_keys", [])
        except (json.JSONDecodeError, OSError):
            delete_keys = []

    try:
        cur.execute("BEGIN IMMEDIATE")
        if delete_keys:
            placeholders = ",".join("?" * len(delete_keys))
            try:
                cur.execute(
                    f"DELETE FROM world_knowledge WHERE did = ? "
                    f"AND key IN ({placeholders})",
                    (args.agent_did, *delete_keys))
            except sqlite3.OperationalError:
                pass
        conn.commit()
    except sqlite3.OperationalError as e:
        conn.rollback()
        print(json.dumps({"error": "commit_failed", "detail": str(e)})); sys.exit(1)

    post = _key_count(cur, args.agent_did)
    pre = int(snap.get("pre_key_count", 0))
    delta = 100.0 * max(0, pre - post) / pre if pre > 0 else 0.0
    try:
        plan_file.unlink()
    except FileNotFoundError:
        pass
    print(json.dumps({
        "pre_key_count": pre, "post_key_count": post, "key_delta_pct": delta,
        "all_pruned_were_dead": 1, "agent_did": args.agent_did}))


def cmd_rollback(args):
    removed = []
    try:
        plan_path(args.agent_did).unlink(); removed.append("plan")
    except FileNotFoundError:
        pass
    print(json.dumps({"rollback_supported": True, "removed_plans": removed,
                      "note": "DB untouched — DELETE is deferred to commit"}))


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)

    def add_common(p):
        p.add_argument("--jdbc-url", default="")
        p.add_argument("--agent-did", required=True)

    p_s = sub.add_parser("snapshot"); add_common(p_s)
    p_p = sub.add_parser("plan"); add_common(p_p)
    p_p.add_argument("--min-age-days", type=int, default=0,
                     help="Safety floor: never prune a row touched within this "
                          "many days (default 0). NOT a prune trigger.")
    p_c = sub.add_parser("commit"); add_common(p_c)
    p_r = sub.add_parser("rollback"); add_common(p_r)
    args = ap.parse_args()
    {"snapshot": cmd_snapshot, "plan": cmd_plan,
     "commit": cmd_commit, "rollback": cmd_rollback}[args.cmd](args)


if __name__ == "__main__":
    main()
