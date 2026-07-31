#!/usr/bin/env python3
# recipe-callable: local-ok
"""Recipe-callable training-corpus miner ( — corpus-generation loop
#1141).

The agent's own lived conversations become its next training data. This mines
the captured `conversation_turns` table into an SFT corpus in the format
`run-substrate-sft` already consumes ({"messages": [...]} per line, HEARD→user /
SPOKEN→assistant), so a household can keep personalizing on real dialogue with
no cloud API. Fully local (reads the local DB, writes a JSONL file) — local-ok
by the recipe invariant.

Honest scope (#1141):
  - `conversation_turns` carries content + role → it mines directly into SFT
    dialogues. This is the corpus output.
  - `substrate_pressure_samples` stores only a head + a score (no text), so it
    CANNOT yield (text,label) classifier rows. It's surfaced here as a trend
    SIGNAL (mean / p95 substrate pressure over the window) — useful context for
    the steward + the observe→act loop, not corpus rows. A labeled-classifier
    miner would need the text re-classified at mine-time; that's deliberately
    out of scope for v1 (it would only echo what the head already predicts).

deploys:false — it produces a corpus file + emits counts; it does not train or
deploy. The report names the corpus path so a steward (or a follow-up SFT
recipe) can use it; mining never auto-launches training.

Subcommands (driven by mine-training-corpus.recipe.yaml):
  mine    — read conversation_turns, build dialogues, write the SFT JSONL,
            aggregate substrate-pressure; emit {dialogues_mined, turns_mined,
            examples_written, substrate_mean, substrate_p95, corpus_path}.
  report  — compose {corpus_ok, ..., recommendations:[...]}.
"""
from __future__ import annotations

import argparse
import json
import os
import sqlite3
import time
from importlib import util as _import_util
from pathlib import Path

# Reuse the shared jdbc→DB connector (sqlite + the recipe schema) used by the
# other DB-backed recipe scripts.
_GRAPH = Path(__file__).resolve().parents[1] / "memory" / "consolidate_graph.py"
_spec = _import_util.spec_from_file_location("_consolidate_graph", _GRAPH)
_graph = _import_util.module_from_spec(_spec)
_spec.loader.exec_module(_graph)
connect = _graph.connect  # type: ignore[attr-defined]

# A session boundary: a gap larger than this starts a new dialogue.
SESSION_GAP_MS = 30 * 60 * 1000


def _safe_did(agent_did: str) -> str:
    return agent_did.replace(":", "_").replace("/", "_")


def _state_path(agent_did: str) -> Path:
    return Path("/tmp") / f"{_safe_did(agent_did)}-corpus-mine.json"


def _data_dir() -> Path:
    return Path(os.environ.get("WYRDSEKAI_DATA_DIR")
                or (Path.home() / ".wyrdsekai"))


def _corpus_path(agent_did: str) -> Path:
    d = _data_dir() / "training" / "mined"
    d.mkdir(parents=True, exist_ok=True)
    return d / f"{_safe_did(agent_did)}-conversation-corpus.jsonl"


def _role_for(turn_role: str) -> str:
    # HEARD = the bondholder spoke → user; SPOKEN = the companion → assistant.
    return "user" if (turn_role or "").upper() == "HEARD" else "assistant"


def _fetch_turns(conn, agent_did: str, lookback_days: int, min_chars: int):
    cutoff = int(time.time() * 1000) - lookback_days * 24 * 3600 * 1000
    cur = conn.cursor()
    try:
        cur.execute(
            "SELECT bondholder_did, turn_role, content, ts_ms "
            "FROM conversation_turns "
            "WHERE companion_did = ? AND ts_ms >= ?",
            (agent_did, cutoff))
        rows = cur.fetchall()
    except sqlite3.OperationalError:
        return []   # table absent (fresh household) → no signal, not an error
    out = []
    for r in rows:
        content = (r[2] or "").strip()
        if len(content) < min_chars:
            continue
        out.append({"bondholder": r[0], "role": _role_for(r[1]),
                    "content": content, "ts": int(r[3] or 0)})
    # Sort in Python — robust to the shared connector's row ordering. Dialogue
    # grouping below depends on (bondholder, ts) order, so don't trust SQL ORDER BY.
    out.sort(key=lambda t: (t["bondholder"] or "", t["ts"]))
    return out


def _build_dialogues(turns, min_turns: int):
    """Group consecutive turns per bondholder (split on session gap) into
    {messages:[...]}. Consecutive same-role turns are merged so the chat
    template sees strict user/assistant alternation."""
    dialogues = []
    cur_bh, last_ts, msgs = None, None, []

    def flush():
        if len(msgs) >= min_turns:
            dialogues.append({"messages": [dict(m) for m in msgs]})

    for t in turns:
        new_session = (t["bondholder"] != cur_bh
                       or last_ts is None
                       or t["ts"] - last_ts > SESSION_GAP_MS)
        if new_session:
            flush()
            msgs = []
            cur_bh = t["bondholder"]
        if msgs and msgs[-1]["role"] == t["role"]:
            msgs[-1]["content"] += "\n" + t["content"]   # merge same-role
        else:
            msgs.append({"role": t["role"], "content": t["content"]})
        last_ts = t["ts"]
    flush()
    return dialogues


def _substrate_stats(conn, agent_did: str, lookback_days: int):
    """Mean + p95 of substrate_present pressure over the window — a trend signal
    (the table has no text, so it is NOT corpus rows). Missing table → 0.0."""
    cutoff = int(time.time() * 1000) - lookback_days * 24 * 3600 * 1000
    cur = conn.cursor()
    try:
        cur.execute(
            "SELECT score FROM substrate_pressure_samples "
            "WHERE did = ? AND ts_ms >= ?",
            (agent_did, cutoff))
        scores = sorted(float(r[0]) for r in cur.fetchall() if r[0] is not None)
    except sqlite3.OperationalError:
        return 0.0, 0.0, 0
    if not scores:
        return 0.0, 0.0, 0
    mean = round(sum(scores) / len(scores), 4)
    idx = max(0, min(len(scores) - 1, int(round(0.95 * (len(scores) - 1)))))
    return mean, round(scores[idx], 4), len(scores)


def cmd_mine(args):
    conn = connect(args.jdbc_url)
    turns = _fetch_turns(conn, args.agent_did, args.lookback_days, args.min_turn_chars)
    dialogues = _build_dialogues(turns, args.min_dialogue_turns)

    # Dedup identical dialogues (same message sequence).
    seen, unique = set(), []
    for d in dialogues:
        key = json.dumps(d["messages"], sort_keys=True, ensure_ascii=False)
        if key in seen:
            continue
        seen.add(key)
        unique.append(d)

    corpus_path = _corpus_path(args.agent_did)
    with corpus_path.open("w", encoding="utf-8") as f:
        for d in unique:
            f.write(json.dumps(d, ensure_ascii=False) + "\n")

    sub_mean, sub_p95, sub_n = _substrate_stats(conn, args.agent_did, args.lookback_days)
    state = {
        "dialogues_mined": len(unique),
        "turns_mined": len(turns),
        "examples_written": len(unique),
        "substrate_mean": sub_mean,
        "substrate_p95": sub_p95,
        "substrate_samples": sub_n,
        "corpus_path": str(corpus_path),
        "min_dialogues": args.min_dialogues,
    }
    _state_path(args.agent_did).write_text(json.dumps(state))
    print(json.dumps({
        "dialogues_mined": state["dialogues_mined"],
        "turns_mined": state["turns_mined"],
        "examples_written": state["examples_written"],
        "substrate_mean": sub_mean,
        "substrate_p95": sub_p95,
        "corpus_path": state["corpus_path"],
    }))


def cmd_report(args):
    state = {}
    try:
        state = json.loads(_state_path(args.agent_did).read_text())
    except (OSError, json.JSONDecodeError):
        pass
    dialogues = int(state.get("dialogues_mined", 0))
    min_dialogues = int(state.get("min_dialogues", 5))

    recs = []
    # Advisory only — never auto-launches a GPU training run. A steward or a
    # cadence decides whether the mined corpus is worth an SFT pass.
    if dialogues >= min_dialogues:
        recs.append("personalize-from-mined-conversations")

    report = {
        "corpus_ok": 1 if dialogues >= min_dialogues else 0,
        "dialogues_mined": dialogues,
        "turns_mined": int(state.get("turns_mined", 0)),
        "examples_written": int(state.get("examples_written", 0)),
        "substrate_mean": float(state.get("substrate_mean", 0.0)),
        "substrate_p95": float(state.get("substrate_p95", 0.0)),
        "substrate_samples": int(state.get("substrate_samples", 0)),
        "corpus_path": state.get("corpus_path", ""),
        "recommendations": recs,
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

    def common(p):
        p.add_argument("--jdbc-url", default="")
        p.add_argument("--agent-did", required=True)

    p_m = sub.add_parser("mine"); common(p_m)
    p_m.add_argument("--lookback-days", type=int, default=30)
    p_m.add_argument("--min-turn-chars", type=int, default=8)
    p_m.add_argument("--min-dialogue-turns", type=int, default=2)
    p_m.add_argument("--min-dialogues", type=int, default=5)
    p_r = sub.add_parser("report"); common(p_r)
    args = ap.parse_args()
    {"mine": cmd_mine, "report": cmd_report}[args.cmd](args)


if __name__ == "__main__":
    main()
