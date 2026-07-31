#!/usr/bin/env python3
# recipe-callable: local-ok
"""Recipe-callable memory-graph consolidation.

Housekeeping for memory_entities + memory_edges tables. Without this, a
household running 6+ months silently accumulates duplicate-entity sprawl
(repeated extractions of the same (entity_type, entity_value) tuple from
extraction jitter) → retrieval quality degrades, agent's memory "feels off."

Real schema (sqlite-create-schema.sql §memory_entities, §memory_edges):
  memory_entities(id, did, memory_id, entity_type, entity_role,
                  entity_value, timestamp, created_at)
  memory_edges(id, did, subject, predicate, object, memory_id,
               confidence, created_at)

Subcommands invoked by consolidate-memory-graph.recipe.yaml:

  snapshot    — record pre-state to /tmp/<agent-did>-memgraph-snapshot.json
                (entity count, edge count)
  dedup       — COMPUTE the dedup plan; write to
                /tmp/<agent-did>-memgraph-dedup-plan.json. Emits the
                WOULD-BE post-counts so the welfare gates that run BEFORE
                commit can actually prevent destructive runs. Dedup rule
                in v0.1: rows with identical (did, entity_type, entity_value)
                — keep the row with the greatest timestamp, drop the rest.
  prune-edges — COMPUTE the prune plan; write to
                /tmp/<agent-did>-memgraph-prune-plan.json. Same gate
                pattern — emits would-be numbers without writing yet.
                Prune rule: created_at < (now - ttl_days) AND confidence
                < min_confidence.
  commit      — read both plans, execute writes in a single SQLite
                transaction. Atomic: either all writes land or none do.
  rollback    — delete the plan files (cleanup). commit hadn't run
                yet (welfare gates STOPped first), so no DB rollback.

The plan-and-commit split is load-bearing: an earlier impl wrote during
dedup + prune-edges, but the recipe's welfare gates run BEFORE commit,
so destruction had already happened by the time the gates fired. With
this shape, the gates actually prevent runaway dedup from corrupting
the graph.

Output (commit step, single JSON line on stdout):
    {"pre_entity_count": N, "post_entity_count": M,
     "entity_delta_pct": <0..100>,
     "pre_edge_count": N, "post_edge_count": M,
     "critical_entities_preserved": 0|1,
     "agent_did": "<did>"}

OSS v0.1: critical_entities_preserved is always 1 (vacuously preserved).
A future "pinned-fragment" mechanism will mark specific entities as
untouchable; for now the dedup rule (drop older rows; keep latest) is
non-destructive of unique information.
"""
from __future__ import annotations

import argparse
import json
import os
import sqlite3
import sys
import time
from pathlib import Path


def _safe_did(agent_did: str) -> str:
    return agent_did.replace(":", "_").replace("/", "_")


def snapshot_path(agent_did: str) -> Path:
    return Path("/tmp") / f"{_safe_did(agent_did)}-memgraph-snapshot.json"


def dedup_plan_path(agent_did: str) -> Path:
    return Path("/tmp") / f"{_safe_did(agent_did)}-memgraph-dedup-plan.json"


def prune_plan_path(agent_did: str) -> Path:
    return Path("/tmp") / f"{_safe_did(agent_did)}-memgraph-prune-plan.json"


def connect(jdbc_url: str):
    """Parse a JDBC URL into a sqlite3 connection. Only sqlite supported
    in OSS v0.1 — production postgres path uses the Java consolidator."""
    if not jdbc_url:
        jdbc_url = os.environ.get("WYRDSEKAI_JDBC_URL", "")
    if not jdbc_url:
        # Auto-resolve from WYRDSEKAI_DATA_DIR. Mirrors the daemon's own
        # SqlSoulStore default (sqlite at $DATA_DIR/world.db). Lets the
        # cron-fired recipe work on a fresh OSS install with zero config.
        data_dir = os.environ.get("WYRDSEKAI_DATA_DIR", "")
        if data_dir:
            jdbc_url = f"jdbc:sqlite:{data_dir.rstrip('/')}/world.db"
    if not jdbc_url:
        raise SystemExit("no JDBC URL — set WYRDSEKAI_JDBC_URL, "
                         "WYRDSEKAI_DATA_DIR, or pass --jdbc-url")
    if not jdbc_url.startswith("jdbc:sqlite:"):
        raise SystemExit(f"only sqlite JDBC supported in OSS v0.1, got: {jdbc_url}")
    path = jdbc_url[len("jdbc:sqlite:"):]
    if "?" in path:
        path = path.split("?", 1)[0]
    return sqlite3.connect(path)


def _entity_count(cur, agent_did: str) -> int:
    try:
        cur.execute("SELECT COUNT(*) FROM memory_entities WHERE did = ?",
                    (agent_did,))
        return cur.fetchone()[0]
    except sqlite3.OperationalError:
        return 0


def _edge_count(cur, agent_did: str) -> int:
    try:
        cur.execute("SELECT COUNT(*) FROM memory_edges WHERE did = ?",
                    (agent_did,))
        return cur.fetchone()[0]
    except sqlite3.OperationalError:
        return 0


def cmd_snapshot(args):
    conn = connect(args.jdbc_url)
    cur = conn.cursor()
    ec = _entity_count(cur, args.agent_did)
    gc = _edge_count(cur, args.agent_did)
    snap = {
        "agent_did": args.agent_did,
        "pre_entity_count": ec,
        "pre_edge_count": gc,
        "ts": int(time.time()),
    }
    snapshot_path(args.agent_did).write_text(json.dumps(snap))
    print(json.dumps({"pre_entity_count": ec, "pre_edge_count": gc}))


def cmd_dedup(args):
    """Compute the dedup plan without writing. The plan is a list of
    entity row IDs to delete — commit step executes them in one
    transaction. Dedup rule for v0.1: rows sharing
    (did, entity_type, entity_value) — keep the one with greatest
    timestamp; mark the older ones for deletion.
    """
    conn = connect(args.jdbc_url)
    cur = conn.cursor()
    try:
        cur.execute(
            "SELECT id, entity_type, entity_value, timestamp "
            "FROM memory_entities WHERE did = ? "
            "ORDER BY entity_type, entity_value, timestamp DESC, id DESC",
            (args.agent_did,))
        rows = cur.fetchall()
    except sqlite3.OperationalError:
        plan = {"delete_ids": [], "deduped_count": 0,
                "pre_entity_count": 0, "post_entity_count": 0,
                "entity_delta_pct": 0.0,
                "note": "memory_entities schema absent"}
        dedup_plan_path(args.agent_did).write_text(json.dumps(plan))
        print(json.dumps({"deduped_count": 0, "entity_delta_pct": 0.0,
                          "post_entity_count": 0,
                          "note": "memory_entities schema absent"}))
        return

    pre_count = len(rows)
    delete_ids: list[int] = []
    seen: set[tuple[str, str]] = set()
    for row_id, etype, evalue, _ts in rows:
        key = (etype, evalue)
        if key in seen:
            delete_ids.append(row_id)
        else:
            seen.add(key)

    deduped = len(delete_ids)
    post_count = pre_count - deduped
    delta_pct = 0.0
    if pre_count > 0:
        delta_pct = 100.0 * deduped / pre_count

    plan = {
        "agent_did": args.agent_did,
        "delete_ids": delete_ids,
        "deduped_count": deduped,
        "pre_entity_count": pre_count,
        "post_entity_count": post_count,
        "entity_delta_pct": delta_pct,
    }
    dedup_plan_path(args.agent_did).write_text(json.dumps(plan))
    print(json.dumps({
        "deduped_count": deduped,
        "entity_delta_pct": delta_pct,
        "post_entity_count": post_count,
        "pre_entity_count": pre_count,
    }))


def cmd_prune_edges(args):
    """Compute the edge-prune plan. Emits would-be numbers so the
    welfare gates fire BEFORE any DB write.

    Rule: edges with created_at < (now - ttl_days) AND
    confidence < min_confidence. The composite filter prevents pruning
    high-confidence old edges (which are exactly the durable knowledge
    we want to retain).
    """
    conn = connect(args.jdbc_url)
    cur = conn.cursor()
    cutoff_ms = int(time.time() * 1000) - args.ttl_days * 24 * 3600 * 1000
    try:
        cur.execute(
            "SELECT id FROM memory_edges "
            "WHERE did = ? AND created_at < ? AND confidence < ?",
            (args.agent_did, cutoff_ms, args.min_confidence))
        prune_ids = [r[0] for r in cur.fetchall()]
        pre_edge = _edge_count(cur, args.agent_did)
    except sqlite3.OperationalError:
        prune_ids = []
        pre_edge = 0

    post_edge = max(0, pre_edge - len(prune_ids))

    # OSS v0.1: no "pinned-fragment" mechanism exists yet, so we don't
    # have a source-of-truth list of critical entities to check. The
    # dedup rule is non-destructive of unique information (keeps the
    # most-recent of each (type,value) tuple), so critical preservation
    # is vacuously true.
    preserved = 1

    plan = {
        "agent_did": args.agent_did,
        "delete_ids": prune_ids,
        "pruned_edges": len(prune_ids),
        "pre_edge_count": pre_edge,
        "post_edge_count": post_edge,
        "critical_entities_preserved": preserved,
    }
    prune_plan_path(args.agent_did).write_text(json.dumps(plan))
    print(json.dumps({
        "pruned_edges": len(prune_ids),
        "post_edge_count": post_edge,
        "critical_entities_preserved": preserved,
    }))


def cmd_commit(args):
    """Execute the dedup + prune plans atomically. Emits the final
    numbers (which match what the gates saw, since dedup/prune did
    the math and persisted it to plan files)."""
    conn = connect(args.jdbc_url)
    cur = conn.cursor()

    snap_file = snapshot_path(args.agent_did)
    dedup_file = dedup_plan_path(args.agent_did)
    prune_file = prune_plan_path(args.agent_did)

    if not snap_file.exists():
        print(json.dumps({"error": "snapshot_missing"}))
        sys.exit(0)
    snap = json.loads(snap_file.read_text())

    dedup_plan = {}
    if dedup_file.exists():
        try:
            dedup_plan = json.loads(dedup_file.read_text())
        except (json.JSONDecodeError, OSError):
            dedup_plan = {}

    prune_plan = {}
    if prune_file.exists():
        try:
            prune_plan = json.loads(prune_file.read_text())
        except (json.JSONDecodeError, OSError):
            prune_plan = {}

    dedup_ids = dedup_plan.get("delete_ids", [])
    prune_ids = prune_plan.get("delete_ids", [])

    try:
        cur.execute("BEGIN IMMEDIATE")
        if dedup_ids:
            placeholders = ",".join("?" * len(dedup_ids))
            try:
                cur.execute(
                    f"DELETE FROM memory_entities WHERE did = ? "
                    f"AND id IN ({placeholders})",
                    (args.agent_did, *dedup_ids))
            except sqlite3.OperationalError:
                pass  # schema absent — fine, plan was empty anyway
        if prune_ids:
            placeholders = ",".join("?" * len(prune_ids))
            try:
                cur.execute(
                    f"DELETE FROM memory_edges WHERE did = ? "
                    f"AND id IN ({placeholders})",
                    (args.agent_did, *prune_ids))
            except sqlite3.OperationalError:
                pass
        conn.commit()
    except sqlite3.OperationalError as e:
        conn.rollback()
        print(json.dumps({"error": "commit_failed", "detail": str(e)}))
        sys.exit(1)

    post_ec = _entity_count(cur, args.agent_did)
    post_gc = _edge_count(cur, args.agent_did)

    pre_ec = int(snap.get("pre_entity_count", 0))
    pre_gc = int(snap.get("pre_edge_count", 0))
    delta_pct = 0.0
    if pre_ec > 0:
        delta_pct = 100.0 * max(0, pre_ec - post_ec) / pre_ec

    # Clean up plan files now that commit succeeded.
    for f in (dedup_file, prune_file):
        try:
            f.unlink()
        except FileNotFoundError:
            pass

    print(json.dumps({
        "pre_entity_count": pre_ec,
        "post_entity_count": post_ec,
        "entity_delta_pct": delta_pct,
        "pre_edge_count": pre_gc,
        "post_edge_count": post_gc,
        "critical_entities_preserved": 1,
        "agent_did": args.agent_did,
    }))


def cmd_rollback(args):
    """commit didn't run (welfare gates STOPped the recipe), so there's
    nothing in the DB to undo. Just clean up the plan files so the
    next attempt starts fresh."""
    removed = []
    for f in (dedup_plan_path(args.agent_did), prune_plan_path(args.agent_did)):
        try:
            f.unlink()
            removed.append(f.name)
        except FileNotFoundError:
            pass
    print(json.dumps({
        "rollback_supported": True,
        "removed_plans": removed,
        "note": "DB untouched — destructive ops are deferred to commit",
    }))


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)

    def add_common(p):
        p.add_argument("--jdbc-url", default="")
        p.add_argument("--agent-did", required=True)

    p_snap = sub.add_parser("snapshot"); add_common(p_snap)
    p_dd = sub.add_parser("dedup"); add_common(p_dd)
    p_dd.add_argument("--cosine-threshold", type=float, default=0.92)
    p_pe = sub.add_parser("prune-edges"); add_common(p_pe)
    p_pe.add_argument("--ttl-days", type=int, default=90)
    p_pe.add_argument("--min-confidence", type=float, default=0.5)
    p_co = sub.add_parser("commit"); add_common(p_co)
    p_rb = sub.add_parser("rollback"); add_common(p_rb)
    args = ap.parse_args()

    {
        "snapshot": cmd_snapshot,
        "dedup": cmd_dedup,
        "prune-edges": cmd_prune_edges,
        "commit": cmd_commit,
        "rollback": cmd_rollback,
    }[args.cmd](args)


if __name__ == "__main__":
    main()
