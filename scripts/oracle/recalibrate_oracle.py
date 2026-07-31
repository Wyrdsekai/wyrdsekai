#!/usr/bin/env python3
# recipe-callable: local-ok
"""Recipe-callable Oracle recalibration (, #1135).

The Oracle is a forecasting/anticipation sidecar (OracleBridge → HTTP service,
default http://localhost:7073). It already self-trains on every Forge sleep
(OracleForgeHook.onForgeSleep → bridge.train) — but UNGOVERNED: no health
check, no accuracy floor, no "did training break the model?" gate, no steward
alert. This recipe converts that into a governed runbook so the household's own
forecaster can't quietly retrain itself into worse (or dead) predictions.

The Oracle talks only over its own local HTTP API, so this is recipe-callable
local-ok (no cloud dependency). It is NOT ship-default-enrolled because Oracle
availability is deployment-dependent: the oracle-core sidecar (a separate repo,
gitlab.com/masmoo/oracle-core) runs on docker-compose household deploys but is
not bundled in native .deb/.pkg/.msi installs. The Java bridge activates on
sidecar reachability (WYRDSEKAI_ORACLE_ENABLED is currently dead config). So the
recipe is on-demand / steward-fired and cleanly NO-OPs (gate-health STOPs) when
the sidecar isn't reachable.

Honest limitations (documented, not band-aided):
  - The sidecar exposes /v1/train + /v1/analyze/* + /v1/feedback + /health, but
    no rich accuracy/backtest endpoint we can rely on. So the accuracy gate is
    DEFENSIVE: it reads /v1/stats if present and enforces a floor + no-
    regression; when no metrics are exposed it does NOT block (you can't gate on
    what you can't measure) — health + predictions-produced still protect.
  - The sidecar trains IN PLACE; there's no model-snapshot endpoint, so the
    gates run AFTER train as tripwires + steward alert, not pre-commit blocks.
    A degraded retrain is detected and surfaced; the fix is retrain with better
    data (the train is deterministic-ish from accumulated events).

Subcommands (driven by recalibrate-oracle.recipe.yaml):
  health      — GET {base}/health → {oracle_healthy: 0|1}
  stats-before— GET {base}/v1/stats (defensive) → {incumbent_accuracy, has_metrics}
  train       — POST {base}/v1/train {user_id} → {trained: 0|1}
  verify      — POST {base}/v1/analyze/anticipate → {predictions_count,
                predictions_ok, post_accuracy, accuracy_ok}

Output is one JSON line per subcommand on stdout; the recipe GATE steps read it.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path


def _incumbent_path(agent_did: str) -> Path:
    safe = agent_did.replace(":", "_").replace("/", "_")
    return Path("/tmp") / f"{safe}-oracle-incumbent.json"


def _base(url_arg: str) -> str:
    base = (url_arg or os.environ.get("WYRDSEKAI_ORACLE_URL")
            or "http://localhost:7073")
    return base[:-1] if base.endswith("/") else base


def _get(base: str, path: str, timeout: float = 5.0):
    try:
        with urllib.request.urlopen(base + path, timeout=timeout) as r:
            return json.loads(r.read().decode("utf-8"))
    except (urllib.error.URLError, OSError, ValueError, TimeoutError):
        return None


def _post(base: str, path: str, body: dict, timeout: float = 60.0):
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(base + path, data=data,
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read().decode("utf-8")
            try:
                return json.loads(raw)
            except ValueError:
                return {"_raw": raw}
    except (urllib.error.URLError, OSError, ValueError, TimeoutError) as e:
        return {"_error": str(e)}


def _extract_accuracy(stats) -> float:
    """Pull a [0,1] accuracy/hit-rate from whatever stats shape the sidecar
    returns. Returns -1.0 when no recognizable metric is present."""
    if not isinstance(stats, dict):
        return -1.0
    for k in ("accuracy", "hit_rate", "precision", "feedback_accuracy",
              "prediction_accuracy"):
        v = stats.get(k)
        if isinstance(v, (int, float)) and 0.0 <= float(v) <= 1.0:
            return float(v)
    return -1.0


def cmd_health(args):
    base = _base(args.oracle_url)
    h = _get(base, "/health")
    healthy = 1 if (isinstance(h, dict) and h.get("status")) else 0
    print(json.dumps({"oracle_healthy": healthy, "oracle_url": base}))


def cmd_stats_before(args):
    base = _base(args.oracle_url)
    stats = _get(base, "/v1/stats")
    acc = _extract_accuracy(stats)
    # Persist the incumbent accuracy so the post-train verify step can compare
    # against it (snapshot-file pattern; avoids threading a step output through
    # command templating).
    _incumbent_path(args.agent_did).write_text(json.dumps({"incumbent_accuracy": acc}))
    print(json.dumps({
        "incumbent_accuracy": acc,
        "has_metrics": 1 if acc >= 0 else 0}))


def cmd_train(args):
    base = _base(args.oracle_url)
    res = _post(base, "/v1/train", {"user_id": args.agent_did})
    trained = 0 if (not isinstance(res, dict) or "_error" in res) else 1
    out = {"trained": trained}
    if isinstance(res, dict) and "_error" in res:
        out["train_error"] = res["_error"][:200]
    print(json.dumps(out))


def cmd_verify(args):
    base = _base(args.oracle_url)
    res = _post(base, "/v1/analyze/anticipate",
                {"user_id": args.agent_did, "min_confidence": 0.5})
    preds = []
    if isinstance(res, dict):
        preds = res.get("insights") or res.get("predictions") or []
    n = len(preds) if isinstance(preds, list) else 0
    predictions_ok = 1 if n > 0 else 0

    stats = _get(base, "/v1/stats")
    post_acc = _extract_accuracy(stats)
    # Read the incumbent accuracy captured pre-train by stats-before.
    incumbent = -1.0
    ipath = _incumbent_path(args.agent_did)
    if ipath.exists():
        try:
            incumbent = float(json.loads(ipath.read_text()).get("incumbent_accuracy", -1.0))
        except (json.JSONDecodeError, OSError, ValueError, TypeError):
            incumbent = -1.0
    # accuracy_ok: PASS when we have no metric to judge by (can't block on the
    # unmeasurable); when a metric exists, enforce floor + no-regression.
    if post_acc < 0:
        accuracy_ok = 1
    else:
        floor_ok = post_acc >= args.min_accuracy
        reg_ok = (incumbent < 0
                  or post_acc >= incumbent - args.regression_epsilon)
        accuracy_ok = 1 if (floor_ok and reg_ok) else 0
    try:
        ipath.unlink()
    except FileNotFoundError:
        pass

    print(json.dumps({
        "predictions_count": n,
        "predictions_ok": predictions_ok,
        "post_accuracy": post_acc,
        "accuracy_ok": accuracy_ok,
        "agent_did": args.agent_did}))


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)

    def add_common(p):
        p.add_argument("--agent-did", required=True)
        p.add_argument("--oracle-url", default="")

    p_h = sub.add_parser("health"); add_common(p_h)
    p_s = sub.add_parser("stats-before"); add_common(p_s)
    p_t = sub.add_parser("train"); add_common(p_t)
    p_v = sub.add_parser("verify"); add_common(p_v)
    p_v.add_argument("--min-accuracy", type=float, default=0.0)
    p_v.add_argument("--regression-epsilon", type=float, default=0.05)
    args = ap.parse_args()
    {"health": cmd_health, "stats-before": cmd_stats_before,
     "train": cmd_train, "verify": cmd_verify}[args.cmd](args)


if __name__ == "__main__":
    main()
