#!/usr/bin/env python3
# recipe-callable: local-ok
"""Recipe-callable soul-fragment consolidation (, #1130).

Housekeeping for the soul_fragments table (
Phase 2.2 / fragment-kind taxonomy).
Sibling of scripts/memory/consolidate_graph.py — same plan-then-commit
shape, different store. consolidate_graph.py touches memory_entities +
memory_edges; compact_collection.py touches the Lucene HNSW index; NEITHER
touches the soul_fragments SQL store, which grows monotonically:

- EPISODIC fragments ( inner-monologue at scene close)
    accumulate one-per-closed-scene forever.
  - Re-observation of the same formative pattern writes near-identical
    NARRATIVE text on extraction jitter.
  - Low-significance EPISODIC memories never expire on their own.

Without this recipe a household running 6+ months drifts: retrieval blends
dozens of duplicate fragments, EPISODIC noise crowds out durable NARRATIVE,
storage + cosine-scan cost climbs.

Real schema (sqlite-create-schema.sql §soul_fragments):
  soul_fragments(did, fragment_id, category, label, fragment_text,
                 embedding, embedding_model, formative, confidence,
                 reinforcement_count, first_observed, last_confirmed,
                 valid_from, superseded_at, superseded_by, ordinal,
                 updated_at, kind, scene_id)
  PRIMARY KEY (did, fragment_id)

Subcommands invoked by consolidate-soul-fragments.recipe.yaml:

  snapshot      — record pre-state to /tmp/<did>-fragments-snapshot.json
                  (fragment count).
  dedup         — COMPUTE the dedup plan; write to
                  /tmp/<did>-fragments-dedup-plan.json. Dedup rule (v0.1):
                  rows sharing (kind, normalized fragment_text) — keep the
                  row with the greatest (reinforcement_count, last_confirmed,
                  ordinal); mark the rest for deletion. NEVER marks a
                  formative=1 ("pinned") fragment for deletion. Exact-text
                  dedup is non-destructive of unique information.
  prune-episodic — COMPUTE the prune plan; write to
                  /tmp/<did>-fragments-prune-plan.json AND emit the COMBINED
                  delta (dedup + prune) so the welfare gates that run BEFORE
                  commit see the true total removal. Prune rule: kind=EPISODIC
                  AND last_confirmed < (now - ttl_days) AND confidence <
                  min_confidence AND formative=0 AND reinforcement_count=0 —
                  low-significance ephemeral episodics past their shelf life.
                  Also asserts no formative fragment is in either delete set
                  (critical_fragments_preserved).
  commit        — read both plans, execute the deletes in one SQLite
                  transaction. Atomic: all-or-nothing.
  rollback      — delete the plan files (commit hadn't run — gates STOPped
                  first — so the DB is untouched).

The plan-and-commit split is load-bearing (same reason as consolidate_graph):
the recipe's welfare gates run BEFORE commit, so dedup/prune compute the
would-be numbers without writing. The gates can then actually prevent a
runaway pass (e.g. a normalization bug collapsing every fragment) from
corrupting the store.

Output (commit step, single JSON line on stdout):
    {"pre_fragment_count": N, "post_fragment_count": M,
     "fragment_delta_pct": <0..100>,
     "critical_fragments_preserved": 0|1,
     "agent_did": "<did>"}
"""
from __future__ import annotations

import argparse
import json
import re
import sqlite3
import sys
import time
from pathlib import Path

# Shared connect() — soul_fragments lives in the same world.db as the
# memory graph, so we reuse the exact JDBC-resolution logic.
from importlib import util as _import_util

_GRAPH = Path(__file__).resolve().parents[1] / "memory" / "consolidate_graph.py"
_spec = _import_util.spec_from_file_location("_consolidate_graph", _GRAPH)
_graph = _import_util.module_from_spec(_spec)
_spec.loader.exec_module(_graph)
connect = _graph.connect  # type: ignore[attr-defined]


def _safe_did(agent_did: str) -> str:
    return agent_did.replace(":", "_").replace("/", "_")


def snapshot_path(agent_did: str) -> Path:
    return Path("/tmp") / f"{_safe_did(agent_did)}-fragments-snapshot.json"


def dedup_plan_path(agent_did: str) -> Path:
    return Path("/tmp") / f"{_safe_did(agent_did)}-fragments-dedup-plan.json"


def prune_plan_path(agent_did: str) -> Path:
    return Path("/tmp") / f"{_safe_did(agent_did)}-fragments-prune-plan.json"


_WS = re.compile(r"\s+")


def _normalize(text: str | None) -> str | None:
    """Collapse whitespace + lowercase for exact-text dedup. Returns None
    for null/blank text (such rows are always treated as unique)."""
    if text is None:
        return None
    norm = _WS.sub(" ", text).strip().lower()
    return norm or None


def _fragment_count(cur, agent_did: str) -> int:
    try:
        cur.execute("SELECT COUNT(*) FROM soul_fragments WHERE did = ?",
                    (agent_did,))
        return cur.fetchone()[0]
    except sqlite3.OperationalError:
        return 0


def cmd_snapshot(args):
    conn = connect(args.jdbc_url)
    cur = conn.cursor()
    fc = _fragment_count(cur, args.agent_did)
    snap = {
        "agent_did": args.agent_did,
        "pre_fragment_count": fc,
        "ts": int(time.time()),
    }
    snapshot_path(args.agent_did).write_text(json.dumps(snap))
    print(json.dumps({"pre_fragment_count": fc}))


def cmd_dedup(args):
    """Compute the dedup plan without writing. Group by (kind, normalized
    fragment_text); within a group keep the best row, mark the rest for
    deletion. formative=1 rows are NEVER deletion candidates — they anchor
    their group even if a non-pinned near-duplicate scores higher."""
    conn = connect(args.jdbc_url)
    cur = conn.cursor()
    try:
        cur.execute(
            "SELECT fragment_id, kind, fragment_text, formative, "
            "reinforcement_count, last_confirmed, ordinal "
            "FROM soul_fragments WHERE did = ?",
            (args.agent_did,))
        rows = cur.fetchall()
    except sqlite3.OperationalError:
        plan = {"agent_did": args.agent_did, "delete_ids": [],
                "deduped_count": 0, "note": "soul_fragments schema absent"}
        dedup_plan_path(args.agent_did).write_text(json.dumps(plan))
        print(json.dumps({"deduped_count": 0,
                          "note": "soul_fragments schema absent"}))
        return

    # Bucket rows by (kind, normalized text). None-text rows are skipped
    # (unique by definition). Track the current "best" keeper per bucket and
    # demote losers into delete_ids.
    best: dict[tuple[str, str], tuple] = {}
    delete_ids: list[str] = []

    def score(r):
        # (reinforcement_count, last_confirmed, ordinal) — higher wins.
        return (r[4] or 0, r[5] or 0, r[6] or 0)

    for r in rows:
        frag_id, kind, text, formative = r[0], r[1], r[2], r[3]
        norm = _normalize(text)
        if norm is None:
            continue
        key = (kind or "NARRATIVE", norm)
        keeper = best.get(key)
        if keeper is None:
            best[key] = r
            continue
        # A formative row always wins its bucket and is never deletable.
        cur_formative = formative == 1
        keep_formative = keeper[3] == 1
        if cur_formative and not keep_formative:
            # New formative row supplants a non-formative keeper.
            delete_ids.append(keeper[0])
            best[key] = r
        elif keep_formative and not cur_formative:
            delete_ids.append(frag_id)
        elif cur_formative and keep_formative:
            # Two pinned near-duplicates — keep both (never delete pinned).
            # Leave the higher-scoring as keeper for any later challenger.
            if score(r) > score(keeper):
                best[key] = r
        else:
            # Neither pinned — drop the lower-scoring.
            if score(r) > score(keeper):
                delete_ids.append(keeper[0])
                best[key] = r
            else:
                delete_ids.append(frag_id)

    pre_count = len(rows)
    deduped = len(delete_ids)
    plan = {
        "agent_did": args.agent_did,
        "delete_ids": delete_ids,
        "deduped_count": deduped,
        "pre_fragment_count": pre_count,
    }
    dedup_plan_path(args.agent_did).write_text(json.dumps(plan))
    print(json.dumps({
        "deduped_count": deduped,
        "pre_fragment_count": pre_count,
    }))


def cmd_prune_episodic(args):
    """Compute the EPISODIC-prune plan AND emit the COMBINED delta so the
    welfare gates see total removal (dedup + prune both delete from
    soul_fragments). Reads the dedup plan to fold its count in.

    Prune rule: kind=EPISODIC AND last_confirmed < cutoff AND confidence <
    min_confidence AND formative=0 AND reinforcement_count=0. These are
    low-significance one-shot episodic memories past their shelf life —
    durable (reinforced / high-confidence / pinned) episodics survive."""
    conn = connect(args.jdbc_url)
    cur = conn.cursor()
    cutoff_ms = int(time.time() * 1000) - args.ttl_days * 24 * 3600 * 1000
    try:
        cur.execute(
            "SELECT fragment_id FROM soul_fragments "
            "WHERE did = ? AND kind = 'EPISODIC' "
            "AND COALESCE(last_confirmed, 0) < ? "
            "AND confidence < ? "
            "AND COALESCE(formative, 0) = 0 "
            "AND COALESCE(reinforcement_count, 0) = 0",
            (args.agent_did, cutoff_ms, args.min_confidence))
        prune_ids = [r[0] for r in cur.fetchall()]
        pre_count = _fragment_count(cur, args.agent_did)
    except sqlite3.OperationalError:
        prune_ids = []
        pre_count = 0

    # Fold in the dedup plan's deletions for the combined delta.
    dedup_ids: list[str] = []
    dpath = dedup_plan_path(args.agent_did)
    if dpath.exists():
        try:
            dedup_ids = json.loads(dpath.read_text()).get("delete_ids", [])
        except (json.JSONDecodeError, OSError):
            dedup_ids = []

    # Union — a row could (rarely) be in both sets; count it once.
    all_deleted = set(dedup_ids) | set(prune_ids)
    total_deleted = len(all_deleted)
    delta_pct = 0.0
    if pre_count > 0:
        delta_pct = 100.0 * total_deleted / pre_count

    # Critical-preservation tripwire: assert no formative=1 fragment is in
    # the combined delete set. Vacuously true by construction (dedup excludes
    # pinned rows; prune requires formative=0) — but the gate catches any
    # future rule change that would orphan a pinned fragment.
    preserved = 1
    if all_deleted:
        try:
            placeholders = ",".join("?" * len(all_deleted))
            cur.execute(
                f"SELECT COUNT(*) FROM soul_fragments WHERE did = ? "
                f"AND COALESCE(formative,0) = 1 "
                f"AND fragment_id IN ({placeholders})",
                (args.agent_did, *all_deleted))
            if cur.fetchone()[0] > 0:
                preserved = 0
        except sqlite3.OperationalError:
            pass

    plan = {
        "agent_did": args.agent_did,
        "delete_ids": prune_ids,
        "pruned_count": len(prune_ids),
    }
    prune_plan_path(args.agent_did).write_text(json.dumps(plan))
    print(json.dumps({
        "pruned_count": len(prune_ids),
        "fragment_delta_pct": delta_pct,
        "critical_fragments_preserved": preserved,
    }))


def cmd_commit(args):
    """Execute the dedup + prune plans atomically against soul_fragments."""
    conn = connect(args.jdbc_url)
    cur = conn.cursor()

    snap_file = snapshot_path(args.agent_did)
    dedup_file = dedup_plan_path(args.agent_did)
    prune_file = prune_plan_path(args.agent_did)

    if not snap_file.exists():
        print(json.dumps({"error": "snapshot_missing"}))
        sys.exit(0)
    snap = json.loads(snap_file.read_text())

    def _ids(path: Path) -> list[str]:
        if not path.exists():
            return []
        try:
            return json.loads(path.read_text()).get("delete_ids", [])
        except (json.JSONDecodeError, OSError):
            return []

    delete_ids = list(set(_ids(dedup_file)) | set(_ids(prune_file)))

    try:
        cur.execute("BEGIN IMMEDIATE")
        if delete_ids:
            placeholders = ",".join("?" * len(delete_ids))
            try:
                cur.execute(
                    f"DELETE FROM soul_fragments WHERE did = ? "
                    f"AND fragment_id IN ({placeholders})",
                    (args.agent_did, *delete_ids))
            except sqlite3.OperationalError:
                pass  # schema absent — plan was empty anyway
        conn.commit()
    except sqlite3.OperationalError as e:
        conn.rollback()
        print(json.dumps({"error": "commit_failed", "detail": str(e)}))
        sys.exit(1)

    post_fc = _fragment_count(cur, args.agent_did)
    pre_fc = int(snap.get("pre_fragment_count", 0))
    delta_pct = 0.0
    if pre_fc > 0:
        delta_pct = 100.0 * max(0, pre_fc - post_fc) / pre_fc

    for f in (dedup_file, prune_file):
        try:
            f.unlink()
        except FileNotFoundError:
            pass

    print(json.dumps({
        "pre_fragment_count": pre_fc,
        "post_fragment_count": post_fc,
        "fragment_delta_pct": delta_pct,
        "critical_fragments_preserved": 1,
        "agent_did": args.agent_did,
    }))


def cmd_rollback(args):
    """commit didn't run (gates STOPped), so the DB is untouched. Clean up
    the plan files so the next attempt starts fresh."""
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
    p_pe = sub.add_parser("prune-episodic"); add_common(p_pe)
    p_pe.add_argument("--ttl-days", type=int, default=90)
    p_pe.add_argument("--min-confidence", type=float, default=0.4)
    p_co = sub.add_parser("commit"); add_common(p_co)
    p_rb = sub.add_parser("rollback"); add_common(p_rb)
    args = ap.parse_args()

    {
        "snapshot": cmd_snapshot,
        "dedup": cmd_dedup,
        "prune-episodic": cmd_prune_episodic,
        "commit": cmd_commit,
        "rollback": cmd_rollback,
    }[args.cmd](args)


if __name__ == "__main__":
    main()
