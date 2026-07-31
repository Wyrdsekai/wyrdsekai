#!/usr/bin/env python3
# recipe-callable: local-ok
"""Recipe-callable library freshness + dead-source prune (
#1136/#1139).

The library analogue of prune-world-knowledge, now a real mutator (#1139): it
enumerates indexed knowledge-chunk provenance from the zone, HTTP-validates each
distinct source URL, and PRUNES the chunks whose source has gone dead (a server
that ANSWERED 4xx/5xx). Merely-unreachable sources (offline / DNS-fail) are
"unchecked" and never pruned, so a transient outage can't empty the index.

Seam (#1139): POST /api/library/freshness/{enumerate,prune-ids} — side-channel
(enumerate reads stored fields, prune deletes by id). NEITHER rewrites a
document, so no chunk loses its dense vector. Welfare gates in the recipe (a
percentage cap AND an absolute cap, both PERMANENT) bound how much one pass may
prune; this script only computes the dead set and applies it.

Local-ok by the recipe invariant: it uses the household's configured web access
(HTTP HEAD to the packs' own source URLs) + the loopback zone REST, never a
cloud LLM key. A fully-offline household prunes nothing.

Sources scanned (all best-effort; missing → empty signal, never an error):
  - Indexed chunks: POST /api/library/freshness/enumerate (id + source per chunk).
  - Pack catalog: $WYRDSEKAI_DATA_DIR/knowledge-packs.json (steward override).
  - Reading log JSON (per-DID or global) under the data dir (for the report).

Subcommands (driven by research-pack-freshness.recipe.yaml):
  scan     — enumerate chunks + read catalog/reading log; write state; emit
             {pack_total, stale_unread, chunk_total}.
  validate — HTTP-HEAD distinct sources, map dead sources → chunk ids; emit
             {checked, dead_sources, dead_chunks, unchecked, dead_chunk_pct,
             chunk_total}.
  prune    — POST the dead chunk ids to prune-ids; emit {pruned, requested}.
  rollback — forward-only note (prune is irreversible; re-acquire is recovery).
  report   — compose {freshness_ok, chunk_total, dead_chunks, pruned,
             pack_total, dead_sources, stale_unread, recommendations:[...]}.
"""
from __future__ import annotations

import argparse
import json
import os
import time
import urllib.error
import urllib.request
from pathlib import Path


def _safe_did(agent_did: str) -> str:
    return agent_did.replace(":", "_").replace("/", "_")


def _state_path(agent_did: str) -> Path:
    return Path("/tmp") / f"{_safe_did(agent_did)}-libfresh.json"


def _data_dir() -> Path:
    return Path(os.environ.get("WYRDSEKAI_DATA_DIR")
                or (Path.home() / ".wyrdsekai"))


def _zone_base() -> str:
    url = os.environ.get("WYRDSEKAI_REST_URL")
    if url:
        return url.rstrip("/")
    port = os.environ.get("WYRDSEKAI_REST_PORT", "7070")
    return f"http://127.0.0.1:{port}"


def _zone_post(path: str, body: dict | None, timeout: float = 30.0):
    """POST to the loopback zone REST. Returns the parsed JSON, or None on any
    failure (zone down / not this node's surface) — callers treat None as 'no
    signal', so the recipe degrades to a no-op rather than crashing."""
    data = json.dumps(body or {}).encode("utf-8")
    req = urllib.request.Request(_zone_base() + path, data=data, method="POST")
    req.add_header("Content-Type", "application/json")
    tok = os.environ.get("WYRDSEKAI_ADMIN_TOKEN")
    if tok:
        req.add_header("X-Wyrdsekai-Admin-Token", tok)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read().decode("utf-8"))
    except (urllib.error.URLError, OSError, ValueError, TimeoutError):
        return None


def _load_json(path: Path):
    try:
        return json.loads(path.read_text())
    except (json.JSONDecodeError, OSError):
        return None


def _catalog_candidates() -> list[Path]:
    d = _data_dir()
    return [d / "knowledge-packs.json", d / "library" / "knowledge-packs.json"]


def _reading_log_candidates(agent_did: str) -> list[Path]:
    d = _data_dir()
    safe = _safe_did(agent_did)
    return [
        d / "library" / f"reading-log-{safe}.json",
        d / "library" / "reading-log.json",
        d / "reading-log.json",
    ]


def _packs_from_catalog() -> list[dict]:
    """Return [{name, source}] from the first readable catalog. Tolerates the
    RegistryFile shape {version, packs:[{name, downloadUrls:[...], source}]}."""
    for c in _catalog_candidates():
        if not c.is_file():
            continue
        doc = _load_json(c)
        if not isinstance(doc, dict):
            continue
        packs = doc.get("packs", [])
        out = []
        for p in packs if isinstance(packs, list) else []:
            if not isinstance(p, dict):
                continue
            name = p.get("name") or p.get("title") or "?"
            src = p.get("source")
            if not src:
                urls = p.get("downloadUrls") or p.get("download_urls") or []
                src = urls[0] if isinstance(urls, list) and urls else None
            out.append({"name": name, "source": src})
        return out
    return []


def _chunks_from_zone(limit: int) -> list[dict]:
    """Enumerate indexed chunk provenance via the zone. Returns [{id, source}]
    (only chunks that carry both). Zone down / no surface → []."""
    resp = _zone_post(f"/api/library/freshness/enumerate?limit={int(limit)}", {})
    if not isinstance(resp, dict):
        return []
    entries = resp.get("entries")
    out = []
    for e in entries if isinstance(entries, list) else []:
        if not isinstance(e, dict):
            continue
        cid = e.get("id")
        src = e.get("source")
        if cid and src:
            out.append({"id": cid, "source": src})
    return out


def _stale_unread_count(agent_did: str, ttl_days: int) -> int:
    """Distinct packs/titles in the reading log whose latest read is older than
    ttl_days. Missing log → 0 (no signal, not an error)."""
    cutoff_ms = int(time.time() * 1000) - ttl_days * 24 * 3600 * 1000
    for c in _reading_log_candidates(agent_did):
        if not c.is_file():
            continue
        doc = _load_json(c)
        entries = doc if isinstance(doc, list) else (
            doc.get("entries") if isinstance(doc, dict) else None)
        if not isinstance(entries, list):
            continue
        latest: dict[str, int] = {}
        for e in entries:
            if not isinstance(e, dict):
                continue
            key = e.get("pack") or e.get("title") or e.get("source") or ""
            ts = e.get("ts_ms") or e.get("timestamp") or e.get("ts") or 0
            try:
                ts = int(ts)
            except (TypeError, ValueError):
                ts = 0
            if 0 < ts < 1_000_000_000_0:
                ts *= 1000
            if key:
                latest[key] = max(latest.get(key, 0), ts)
        return sum(1 for v in latest.values() if v < cutoff_ms)
    return 0


def cmd_scan(args):
    packs = _packs_from_catalog()
    chunks = _chunks_from_zone(args.enumerate_limit)
    stale = _stale_unread_count(args.agent_did, args.reading_ttl_days)
    state = {"packs": packs, "chunks": chunks, "stale_unread": stale}
    _state_path(args.agent_did).write_text(json.dumps(state))
    print(json.dumps({"pack_total": len(packs),
                      "chunk_total": len(chunks),
                      "stale_unread": stale}))


def _head_ok(url: str, timeout: float) -> bool | None:
    """True = reachable, False = dead (4xx/5xx — server answered gone/forbidden),
    None = couldn't check (offline / DNS / timeout)."""
    if not url or not url.lower().startswith(("http://", "https://")):
        return None
    req = urllib.request.Request(url, method="HEAD")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return 200 <= getattr(r, "status", 200) < 400
    except urllib.error.HTTPError as e:
        return e.code < 400
    except (urllib.error.URLError, OSError, ValueError, TimeoutError):
        return None


def cmd_validate(args):
    state = _load_json(_state_path(args.agent_did)) or {}
    packs = state.get("packs", [])
    chunks = state.get("chunks", [])
    if not isinstance(chunks, list):
        chunks = []

    # Distinct source URLs across catalog packs + indexed chunks.
    sources = set()
    for p in packs if isinstance(packs, list) else []:
        if p.get("source"):
            sources.add(p["source"])
    for c in chunks:
        if c.get("source"):
            sources.add(c["source"])

    # One HEAD per distinct source.
    status: dict[str, bool | None] = {}
    for src in sources:
        status[src] = _head_ok(src, args.timeout)

    checked = sum(1 for v in status.values() if v is not None)
    unchecked = sum(1 for v in status.values() if v is None)
    dead_sources = sum(1 for v in status.values() if v is False)

    # Map dead sources → the chunk ids that cite them (the prune set).
    dead_ids = [c["id"] for c in chunks if status.get(c.get("source")) is False]
    chunk_total = len(chunks)
    dead_chunks = len(dead_ids)
    dead_chunk_pct = round(100.0 * dead_chunks / chunk_total, 2) if chunk_total else 0.0

    dead_src_list = [s for s, v in status.items() if v is False][:10]
    state.update({
        "checked_sources": checked,
        "unchecked_sources": unchecked,
        "dead_sources": dead_sources,
        "dead_src_list": dead_src_list,
        "dead_ids": dead_ids,
        "dead_chunks": dead_chunks,
        "chunk_total": chunk_total,
        "dead_chunk_pct": dead_chunk_pct,
    })
    _state_path(args.agent_did).write_text(json.dumps(state))
    print(json.dumps({
        "checked": checked,
        "unchecked": unchecked,
        "dead_sources": dead_sources,
        "dead_chunks": dead_chunks,
        "chunk_total": chunk_total,
        "dead_chunk_pct": dead_chunk_pct,
    }))


def cmd_prune(args):
    state = _load_json(_state_path(args.agent_did)) or {}
    dead_ids = state.get("dead_ids", [])
    if not isinstance(dead_ids, list) or not dead_ids:
        state["pruned"] = 0
        _state_path(args.agent_did).write_text(json.dumps(state))
        print(json.dumps({"requested": 0, "pruned": 0}))
        return
    resp = _zone_post("/api/library/freshness/prune-ids", {"ids": dead_ids})
    pruned = int(resp.get("pruned", 0)) if isinstance(resp, dict) else 0
    state["pruned"] = pruned
    _state_path(args.agent_did).write_text(json.dumps(state))
    print(json.dumps({"requested": len(dead_ids), "pruned": pruned}))


def cmd_rollback(args):
    # Prune is forward-only: a dead source's chunks can't be undeleted (and the
    # source is gone anyway). Recovery is re-acquisition through the library.
    # The PERMANENT gates upstream are what prevent an over-prune in the first
    # place — this is a structured no-op so the recipe's rollback contract holds.
    print(json.dumps({
        "rolled_back": 0,
        "note": "prune is forward-only; re-acquire dead sources via the library",
        "agent_did": args.agent_did,
    }))


def cmd_report(args):
    state = _load_json(_state_path(args.agent_did)) or {}
    packs = state.get("packs", [])
    pack_total = len(packs) if isinstance(packs, list) else 0
    chunk_total = int(state.get("chunk_total", 0))
    dead_chunks = int(state.get("dead_chunks", 0))
    pruned = int(state.get("pruned", 0))
    dead_sources = int(state.get("dead_sources", 0))
    stale_unread = int(state.get("stale_unread", 0))

    recs = []
    if dead_sources > 0:
        recs.append("re-acquire-dead-sources")
    if stale_unread > 0:
        recs.append("review-stale-unread-packs")

    report = {
        "freshness_ok": 1 if not recs and pruned == 0 else 0,
        "pack_total": pack_total,
        "chunk_total": chunk_total,
        "dead_sources": dead_sources,
        "dead_chunks": dead_chunks,
        "pruned": pruned,
        "stale_unread": stale_unread,
        "checked": int(state.get("checked_sources", 0)),
        "unchecked": int(state.get("unchecked_sources", 0)),
        "dead_chunk_pct": float(state.get("dead_chunk_pct", 0.0)),
        "recommendations": recs,
        "dead_src_list": state.get("dead_src_list", []),
        "agent_did": args.agent_did,
    }
    try:
        _state_path(args.agent_did).unlink()
    except FileNotFoundError:
        pass
    print(json.dumps(report))


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)

    def add_common(p):
        p.add_argument("--agent-did", required=True)

    p_s = sub.add_parser("scan"); add_common(p_s)
    p_s.add_argument("--reading-ttl-days", type=int, default=120)
    p_s.add_argument("--enumerate-limit", type=int, default=2000)
    p_v = sub.add_parser("validate"); add_common(p_v)
    p_v.add_argument("--timeout", type=float, default=5.0)
    p_p = sub.add_parser("prune"); add_common(p_p)
    p_rb = sub.add_parser("rollback"); add_common(p_rb)
    p_r = sub.add_parser("report"); add_common(p_r)
    args = ap.parse_args()
    {"scan": cmd_scan, "validate": cmd_validate, "prune": cmd_prune,
     "rollback": cmd_rollback, "report": cmd_report}[args.cmd](args)


if __name__ == "__main__":
    main()
