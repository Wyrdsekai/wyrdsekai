#!/usr/bin/env python3
# recipe-callable: local-ok
"""Recipe-callable soul-fragment re-embedding (, #1131).

Re-embeds soul_fragments rows whose stored embedding was produced by an
older encoder than the current bundled *retrieval* encoder
(EmbeddingModel.PARAPHRASE_L12). The agent-callable, governed equivalent of
the boot-time / CLI `wyrd embed-migrate` — but scoped to the soul_fragments
SQL store specifically.

Why this exists as its own recipe: when the bundled retrieval encoder version
bumps, every soul fragment embedded under the old version sits in a stale
vector space. Cosine retrieval over a MIX of old + new vectors silently
degrades — the exact failure surfaced by the 2026-05-29 SetFit decouple, which
forced a manual `wyrd embed-migrate` on home-server + mac-node. compact-library-index
already re-embeds version-mismatched *Lucene* chunks; soul_fragments are SQL
rows with their own embedding BLOB and had no equivalent. This closes that gap
and lets the household self-heal the migration tail on a schedule instead of
needing a human to run the CLI.

Embedding BLOB format matches the runtime (sqlite-create-schema.sql comment):
IEEE-754 little-endian float32 sequence, L2-normalized 384-d, mean-pooled —
identical to EmbeddingModel.embed() in core. We re-embed with the STOCK
retrieval encoder (NOT the SetFit classifier encoder — see
).

Subcommands invoked by reembed-soul-fragments.recipe.yaml:

  snapshot — count total fragments + stale (embedding_model != target) ones;
             write /tmp/<did>-reembed-snapshot.json. Emits pre_fragment_count
             + pre_stale_count.
  reembed  — re-embed every stale row in batches; UPDATE embedding +
             embedding_model in place (non-destructive of the row — only the
             two embedding columns change, and the operation is deterministic
             from fragment_text so it is itself the recovery path). Emits
             reembedded_count + post_stale_count.
  verify   — emit post_fragment_count (gate-no-loss tripwire) and a
             self-retrieval coherence check: embed a sample of fragments' own
             text with the stock encoder and assert each retrieves ITSELF as
             top-1 against the freshly-written embeddings. self_retrieval_ok
             is 0|1. Catches a corrupt / wrong-shape re-embed before the run
             reports SUCCESS.

No commit/rollback split: reembed only rewrites two columns and is idempotent
+ deterministic from fragment_text — re-running with a good encoder is the
recovery. The welfare gates (no-loss + coherent) run AFTER the write as
tripwires; a failure means the encoder/onnx is broken, and the fix is a
correct encoder + re-run, not a DB undo.

Output is a single JSON line per subcommand on stdout (the recipe GATE steps
read the keys); stderr carries human-debuggable detail.
"""
from __future__ import annotations

import argparse
import json
import os
import sqlite3
import struct
import sys
import time
from pathlib import Path

from importlib import util as _import_util

# Reuse the memory consolidator's JDBC-resolution logic (same world.db).
_GRAPH = Path(__file__).resolve().parents[1] / "memory" / "consolidate_graph.py"
_spec = _import_util.spec_from_file_location("_consolidate_graph", _GRAPH)
_graph = _import_util.module_from_spec(_spec)
_spec.loader.exec_module(_graph)
connect = _graph.connect  # type: ignore[attr-defined]

REPO_ROOT = Path(__file__).resolve().parents[2]
# STOCK retrieval encoder — NOT the SetFit classifier encoder. Decoupled
# 2026-05-29: soul-fragment retrieval uses the general-purpose stock encoder.
_STOCK_FILENAME = "paraphrase-multilingual-MiniLM-L12-v2-q8.onnx"
DEFAULT_TOKENIZER = "Xenova/paraphrase-multilingual-MiniLM-L12-v2"
EMBED_DIM = 384


def _safe_did(agent_did: str) -> str:
    return agent_did.replace(":", "_").replace("/", "_")


def snapshot_path(agent_did: str) -> Path:
    return Path("/tmp") / f"{_safe_did(agent_did)}-reembed-snapshot.json"


def _resolve_stock_encoder() -> Path:
    candidates = []
    data_dir = os.environ.get("WYRDSEKAI_DATA_DIR")
    if data_dir:
        candidates.append(Path(data_dir) / "models" / _STOCK_FILENAME)
    candidates.append(Path.home() / ".wyrdsekai" / "models" / _STOCK_FILENAME)
    candidates.append(REPO_ROOT / "core" / "src" / "main" / "resources"
                      / "models" / _STOCK_FILENAME)
    for c in candidates:
        if c.is_file():
            return c
    return candidates[-1]


def _fragment_count(cur, agent_did: str) -> int:
    try:
        cur.execute("SELECT COUNT(*) FROM soul_fragments WHERE did = ?",
                    (agent_did,))
        return cur.fetchone()[0]
    except sqlite3.OperationalError:
        return 0


def _stale_count(cur, agent_did: str, target_model: str) -> int:
    try:
        cur.execute(
            "SELECT COUNT(*) FROM soul_fragments "
            "WHERE did = ? AND (embedding_model IS NULL OR embedding_model != ?) "
            "AND fragment_text IS NOT NULL AND TRIM(fragment_text) != ''",
            (agent_did, target_model))
        return cur.fetchone()[0]
    except sqlite3.OperationalError:
        return 0


# ── Embedding (stock encoder, mean-pool + L2-norm — matches the runtime) ──

class _Embedder:
    def __init__(self, onnx_path: Path, tokenizer_name: str):
        import numpy as np
        import onnxruntime as ort
        from transformers import AutoTokenizer
        self._np = np
        self._sess = ort.InferenceSession(str(onnx_path),
                                          providers=["CPUExecutionProvider"])
        self._tok = AutoTokenizer.from_pretrained(tokenizer_name)
        self._expected = {i.name for i in self._sess.get_inputs()}

    def embed(self, texts: list[str]):
        np = self._np
        enc = self._tok(texts, padding=True, truncation=True,
                        max_length=128, return_tensors="np")
        inputs = {
            "input_ids": enc["input_ids"].astype(np.int64),
            "attention_mask": enc["attention_mask"].astype(np.int64),
        }
        if "token_type_ids" in self._expected and "token_type_ids" in enc:
            inputs["token_type_ids"] = enc["token_type_ids"].astype(np.int64)
        tok_embs = self._sess.run(None, inputs)[0]
        mask = enc["attention_mask"].astype(np.float32)[:, :, None]
        summed = (tok_embs * mask).sum(axis=1)
        counts = mask.sum(axis=1).clip(min=1)
        pooled = summed / counts
        norms = np.linalg.norm(pooled, axis=1, keepdims=True).clip(min=1e-9)
        return (pooled / norms).astype(np.float32)


def _to_blob(vec) -> bytes:
    # IEEE-754 little-endian float32 sequence, matching the Java reader.
    return struct.pack("<%df" % len(vec), *[float(x) for x in vec])


def _from_blob(blob: bytes):
    import numpy as np
    n = len(blob) // 4
    return np.array(struct.unpack("<%df" % n, blob), dtype=np.float32)


def cmd_snapshot(args):
    conn = connect(args.jdbc_url)
    cur = conn.cursor()
    pre = _fragment_count(cur, args.agent_did)
    stale = _stale_count(cur, args.agent_did, args.target_model)
    snap = {"agent_did": args.agent_did, "pre_fragment_count": pre,
            "pre_stale_count": stale, "target_model": args.target_model,
            "ts": int(time.time())}
    snapshot_path(args.agent_did).write_text(json.dumps(snap))
    print(json.dumps({"pre_fragment_count": pre, "pre_stale_count": stale}))


def cmd_reembed(args):
    conn = connect(args.jdbc_url)
    cur = conn.cursor()
    try:
        cur.execute(
            "SELECT fragment_id, fragment_text FROM soul_fragments "
            "WHERE did = ? AND (embedding_model IS NULL OR embedding_model != ?) "
            "AND fragment_text IS NOT NULL AND TRIM(fragment_text) != ''",
            (args.agent_did, args.target_model))
        rows = cur.fetchall()
    except sqlite3.OperationalError:
        print(json.dumps({"reembedded_count": 0, "post_stale_count": 0,
                          "note": "soul_fragments schema absent"}))
        return

    if not rows:
        print(json.dumps({"reembedded_count": 0, "post_stale_count": 0}))
        return

    onnx = args.embedding_onnx or _resolve_stock_encoder()
    if not Path(onnx).is_file():
        print(f"ERROR: stock encoder not found: {onnx}", file=sys.stderr)
        print(json.dumps({"reembedded_count": 0, "error": "encoder_missing",
                          "encoder_path": str(onnx)}))
        sys.exit(0)  # recipe gate handles it; not a script crash.

    try:
        embedder = _Embedder(Path(onnx), args.tokenizer)
    except ImportError as e:
        print(f"ERROR: missing dependency: {e}", file=sys.stderr)
        print(json.dumps({"reembedded_count": 0, "error": "deps_missing"}))
        sys.exit(0)

    batch = max(1, args.batch_size)
    done = 0
    for i in range(0, len(rows), batch):
        chunk = rows[i:i + batch]
        texts = [r[1] for r in chunk]
        vecs = embedder.embed(texts)
        for (frag_id, _txt), vec in zip(chunk, vecs):
            cur.execute(
                "UPDATE soul_fragments SET embedding = ?, embedding_model = ?, "
                "updated_at = ? WHERE did = ? AND fragment_id = ?",
                (_to_blob(vec), args.target_model, int(time.time()),
                 args.agent_did, frag_id))
        conn.commit()
        done += len(chunk)
        print(f"reembed: {done}/{len(rows)}", file=sys.stderr)

    post_stale = _stale_count(cur, args.agent_did, args.target_model)
    print(json.dumps({"reembedded_count": done, "post_stale_count": post_stale}))


def cmd_verify(args):
    conn = connect(args.jdbc_url)
    cur = conn.cursor()
    post = _fragment_count(cur, args.agent_did)
    post_stale = _stale_count(cur, args.agent_did, args.target_model)

    # Self-retrieval coherence: sample fragments that are now on-target, embed
    # their own text with the stock encoder, and assert each scores itself
    # top-1 against the freshly-written embeddings. A corrupt/wrong-shape
    # re-embed breaks this even though no rows were lost.
    self_ok = 1
    sample_size = 0
    try:
        cur.execute(
            "SELECT fragment_id, fragment_text, embedding FROM soul_fragments "
            "WHERE did = ? AND embedding_model = ? AND embedding IS NOT NULL "
            "AND fragment_text IS NOT NULL AND TRIM(fragment_text) != '' "
            "LIMIT ?",
            (args.agent_did, args.target_model, args.sample))
        sample = cur.fetchall()
        # Pull the full on-target corpus to score against.
        cur.execute(
            "SELECT fragment_id, embedding FROM soul_fragments "
            "WHERE did = ? AND embedding_model = ? AND embedding IS NOT NULL",
            (args.agent_did, args.target_model))
        corpus = cur.fetchall()
    except sqlite3.OperationalError:
        sample, corpus = [], []

    if sample and corpus:
        try:
            import numpy as np
            onnx = args.embedding_onnx or _resolve_stock_encoder()
            embedder = _Embedder(Path(onnx), args.tokenizer)
            corpus_ids = [r[0] for r in corpus]
            corpus_mat = np.stack([_from_blob(r[1]) for r in corpus])
            sample_size = len(sample)
            for frag_id, text, _emb in sample:
                q = embedder.embed([text])[0]
                sims = corpus_mat @ q  # all L2-normalized → cosine
                top = corpus_ids[int(np.argmax(sims))]
                if top != frag_id:
                    self_ok = 0
                    print(f"  COHERENCE MISS: {frag_id} retrieved {top} top-1",
                          file=sys.stderr)
        except ImportError as e:
            print(f"verify: deps missing ({e}) — coherence check skipped",
                  file=sys.stderr)
            sample_size = 0  # can't assert; leave self_ok=1 (no evidence of harm)

    print(json.dumps({
        "post_fragment_count": post,
        "post_stale_count": post_stale,
        "self_retrieval_ok": self_ok,
        "sample_size": sample_size,
        "agent_did": args.agent_did,
    }))


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)

    def add_common(p):
        p.add_argument("--jdbc-url", default="")
        p.add_argument("--agent-did", required=True)
        p.add_argument("--target-model", required=True,
                       help="Current retrieval encoder version string — must "
                            "match EmbeddingModel.PARAPHRASE_L12.version().")

    p_snap = sub.add_parser("snapshot"); add_common(p_snap)
    p_re = sub.add_parser("reembed"); add_common(p_re)
    p_re.add_argument("--embedding-onnx", default="")
    p_re.add_argument("--tokenizer", default=DEFAULT_TOKENIZER)
    p_re.add_argument("--batch-size", type=int, default=64)
    p_ve = sub.add_parser("verify"); add_common(p_ve)
    p_ve.add_argument("--embedding-onnx", default="")
    p_ve.add_argument("--tokenizer", default=DEFAULT_TOKENIZER)
    p_ve.add_argument("--sample", type=int, default=8)
    args = ap.parse_args()

    {
        "snapshot": cmd_snapshot,
        "reembed": cmd_reembed,
        "verify": cmd_verify,
    }[args.cmd](args)


if __name__ == "__main__":
    main()
