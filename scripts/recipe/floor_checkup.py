#!/usr/bin/env python3
# recipe-callable: local-ok
"""Recipe-callable welfare-floor checkup (, #1132).

The first OBSERVABILITY recipe — read-only, deploys:false. Every other recipe
runs only when there is something to *change* (deploys:true). None periodically
asks "are my floors still met? is maintenance due?" and surfaces drift WITHOUT
mutating anything. This is that recipe's worker.

It scans the household's own SQL stores and emits a structured health report.
The report flows to the steward via the scheduler's Chronicle/Forge ingestion
of completed runs (RecipeChronicleSynthesizer / RecipeForgeIngester). Nothing
is deployed, pruned, re-embedded, or trained — the checkup only *looks* and
*reports*. A scheduled retrieval-staleness signal here would have flagged the
2026-05-29 SetFit migration tail on its own instead of it surfacing by accident
during a test run.

Signals (all cheap, deterministic, CPU-only, no ONNX, no inference):
  fragments-health  — soul_fragments: total, stale-embedding count, near-
                      duplicate-text count, overdue low-significance EPISODIC
                      count.
  graph-health      — memory_entities/edges: entity total, duplicate
                      (type,value) count, edge total, overdue low-confidence
                      edge count.
  report            — composes the two into a single report with a
                      maintenance_due list (which maintenance recipes the
                      numbers suggest running) and checkup_ok (1 iff nothing
                      is flagged). Informational only — there are no welfare
                      gates; the recipe never STOPs and never mutates.

Output is one JSON line per subcommand on stdout; intermediate counts are also
written to /tmp/<did>-checkup-*.json so the report step can compose them.
"""
from __future__ import annotations

import argparse
import json
import re
import sqlite3
import time
from pathlib import Path

from importlib import util as _import_util

_GRAPH = Path(__file__).resolve().parents[1] / "memory" / "consolidate_graph.py"
_spec = _import_util.spec_from_file_location("_consolidate_graph", _GRAPH)
_graph = _import_util.module_from_spec(_spec)
_spec.loader.exec_module(_graph)
connect = _graph.connect  # type: ignore[attr-defined]

# Default stock retrieval encoder version — staleness is "embedding_model !=
# this". Keep in lockstep with EmbeddingModel.PARAPHRASE_L12.version().
DEFAULT_TARGET_MODEL = "multilingual-MiniLM-L12-v2-2026-04-30"
_WS = re.compile(r"\s+")


def _safe_did(agent_did: str) -> str:
    return agent_did.replace(":", "_").replace("/", "_")


def _frag_path(agent_did: str) -> Path:
    return Path("/tmp") / f"{_safe_did(agent_did)}-checkup-fragments.json"


def _graph_path(agent_did: str) -> Path:
    return Path("/tmp") / f"{_safe_did(agent_did)}-checkup-graph.json"


def _norm(text):
    if text is None:
        return None
    n = _WS.sub(" ", text).strip().lower()
    return n or None


def cmd_fragments_health(args):
    conn = connect(args.jdbc_url)
    cur = conn.cursor()
    total = stale = dup = overdue = 0
    cutoff_ms = int(time.time() * 1000) - args.ttl_days * 24 * 3600 * 1000
    try:
        cur.execute("SELECT COUNT(*) FROM soul_fragments WHERE did = ?",
                    (args.agent_did,))
        total = cur.fetchone()[0]
        cur.execute(
            "SELECT COUNT(*) FROM soul_fragments WHERE did = ? "
            "AND (embedding_model IS NULL OR embedding_model != ?) "
            "AND fragment_text IS NOT NULL AND TRIM(fragment_text) != ''",
            (args.agent_did, args.target_model))
        stale = cur.fetchone()[0]
        cur.execute(
            "SELECT COUNT(*) FROM soul_fragments WHERE did = ? "
            "AND kind = 'EPISODIC' AND COALESCE(last_confirmed,0) < ? "
            "AND confidence < ? AND COALESCE(formative,0) = 0 "
            "AND COALESCE(reinforcement_count,0) = 0",
            (args.agent_did, cutoff_ms, args.confidence_min))
        overdue = cur.fetchone()[0]
        # Near-duplicate count: rows beyond the first in each (kind, norm-text)
        # bucket. Normalization is whitespace+lowercase, done in Python.
        cur.execute(
            "SELECT kind, fragment_text FROM soul_fragments WHERE did = ?",
            (args.agent_did,))
        seen = set()
        for kind, text in cur.fetchall():
            n = _norm(text)
            if n is None:
                continue
            key = (kind or "NARRATIVE", n)
            if key in seen:
                dup += 1
            else:
                seen.add(key)
    except sqlite3.OperationalError:
        pass

    out = {"fragment_total": total, "fragment_stale_embed": stale,
           "fragment_dup_text": dup, "fragment_episodic_overdue": overdue}
    _frag_path(args.agent_did).write_text(json.dumps(out))
    print(json.dumps(out))


def cmd_graph_health(args):
    conn = connect(args.jdbc_url)
    cur = conn.cursor()
    ent_total = ent_dup = edge_total = edge_overdue = 0
    cutoff_ms = int(time.time() * 1000) - args.ttl_days * 24 * 3600 * 1000
    try:
        cur.execute("SELECT COUNT(*) FROM memory_entities WHERE did = ?",
                    (args.agent_did,))
        ent_total = cur.fetchone()[0]
        # Duplicate (type,value) tuples beyond the first occurrence.
        cur.execute(
            "SELECT COUNT(*) - COUNT(DISTINCT entity_type || '\x1f' || entity_value) "
            "FROM memory_entities WHERE did = ?", (args.agent_did,))
        ent_dup = max(0, cur.fetchone()[0] or 0)
    except sqlite3.OperationalError:
        pass
    try:
        cur.execute("SELECT COUNT(*) FROM memory_edges WHERE did = ?",
                    (args.agent_did,))
        edge_total = cur.fetchone()[0]
        cur.execute(
            "SELECT COUNT(*) FROM memory_edges WHERE did = ? "
            "AND created_at < ? AND confidence < ?",
            (args.agent_did, cutoff_ms, args.confidence_min))
        edge_overdue = cur.fetchone()[0]
    except sqlite3.OperationalError:
        pass

    out = {"entity_total": ent_total, "entity_dup": ent_dup,
           "edge_total": edge_total, "edge_overdue": edge_overdue}
    _graph_path(args.agent_did).write_text(json.dumps(out))
    print(json.dumps(out))


def cmd_report(args):
    def _read(p: Path) -> dict:
        if p.exists():
            try:
                return json.loads(p.read_text())
            except (json.JSONDecodeError, OSError):
                return {}
        return {}

    frag = _read(_frag_path(args.agent_did))
    grph = _read(_graph_path(args.agent_did))

    due = []
    if frag.get("fragment_stale_embed", 0) > 0:
        due.append("reembed-soul-fragments")
    if (frag.get("fragment_dup_text", 0) > 0
            or frag.get("fragment_episodic_overdue", 0) > 0):
        due.append("consolidate-soul-fragments")
    if grph.get("entity_dup", 0) > 0 or grph.get("edge_overdue", 0) > 0:
        due.append("consolidate-memory-graph")
    # Stable de-dup preserving order.
    seen = set()
    maintenance_due = [r for r in due if not (r in seen or seen.add(r))]

    report = {
        "checkup_ok": 1 if not maintenance_due else 0,
        "maintenance_due": maintenance_due,
        "fragment_total": frag.get("fragment_total", 0),
        "fragment_stale_embed": frag.get("fragment_stale_embed", 0),
        "fragment_dup_text": frag.get("fragment_dup_text", 0),
        "fragment_episodic_overdue": frag.get("fragment_episodic_overdue", 0),
        "entity_total": grph.get("entity_total", 0),
        "entity_dup": grph.get("entity_dup", 0),
        "edge_total": grph.get("edge_total", 0),
        "edge_overdue": grph.get("edge_overdue", 0),
        "agent_did": args.agent_did,
    }
    # Clean up intermediate files now that the report is composed.
    for p in (_frag_path(args.agent_did), _graph_path(args.agent_did)):
        try:
            p.unlink()
        except FileNotFoundError:
            pass
    print(json.dumps(report))


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)

    def add_common(p):
        p.add_argument("--jdbc-url", default="")
        p.add_argument("--agent-did", required=True)

    p_f = sub.add_parser("fragments-health"); add_common(p_f)
    p_f.add_argument("--target-model", default=DEFAULT_TARGET_MODEL)
    p_f.add_argument("--ttl-days", type=int, default=90)
    p_f.add_argument("--confidence-min", type=float, default=0.4)
    p_g = sub.add_parser("graph-health"); add_common(p_g)
    p_g.add_argument("--ttl-days", type=int, default=90)
    p_g.add_argument("--confidence-min", type=float, default=0.5)
    p_r = sub.add_parser("report"); add_common(p_r)
    args = ap.parse_args()

    {
        "fragments-health": cmd_fragments_health,
        "graph-health": cmd_graph_health,
        "report": cmd_report,
    }[args.cmd](args)


if __name__ == "__main__":
    main()
