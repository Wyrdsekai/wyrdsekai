#!/usr/bin/env python3
# recipe-callable: local-ok
"""Recipe-callable param tuner ( #1142 — the tune-recipe-params loop).

A recipe whose product is *other recipes' soft defaults*. It reads a target
recipe's outcome history (success/fail counts over the queue window) and, when
the failure rate is high enough, nudges that recipe's SOFT params — timeouts,
lookback windows, advisory thresholds — UP within a hard, multiplier-bounded
envelope, persisting the new default via the zone's tune endpoint so future runs
pick it up.

The single safety invariant lives server-side in RecipeParamTuner.validateNudge
(re-run at the apply endpoint): a param referenced by a PERMANENT welfare gate
condition (OPEN-R4, #1013) is a load-bearing floor and is REFUSED — the tuner
can never move it. This script honours the same rule client-side (it skips any
param the stats endpoint flags `floorProtected`), so a floor is double-guarded.

Bounded by construction:
  - never lowers a param (v1 only ever increases headroom — lowering a timeout
    could induce the very failures we're reacting to);
  - never below the recipe's manifest default (the floor of the envelope);
  - never above manifestDefault * abs_max_mult (the ceiling) — so a runaway
    loop firing every cadence can't push a timeout to infinity; it asymptotes;
  - only touches params whose name matches the `tunable` substring allowlist.

Fully local: talks to the loopback zone REST (same node), no cloud API.
deploys:false — it changes config (a soft, reversible override row), not a
production model/vector artifact; the floor-protection (not recipe gates) is the
guard, so the recipe is honestly gateless.

Subcommands (driven by tune-recipe-params.recipe.yaml):
  tune    — GET stats, compute bounded nudges, POST apply each; emit
            {target_recipe, total_runs, fail_rate, tuned_count, refused_count,
             skipped_floor_count, tuned_params}.
  report  — compose {tuned_ok, ..., recommendations:[...]}.
"""
from __future__ import annotations

import argparse
import json
import os
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


def _safe(s: str) -> str:
    return (s or "").replace(":", "_").replace("/", "_")


def _state_path(target_recipe: str, agent_did: str) -> Path:
    return Path("/tmp") / f"{_safe(target_recipe)}-{_safe(agent_did)}-tune.json"


def _zone_base() -> str:
    url = os.environ.get("WYRDSEKAI_REST_URL")
    if url:
        return url.rstrip("/")
    port = os.environ.get("WYRDSEKAI_REST_PORT", "7070")
    return f"http://127.0.0.1:{port}"


def _admin_headers(req: urllib.request.Request) -> None:
    tok = os.environ.get("WYRDSEKAI_ADMIN_TOKEN")
    if tok:
        req.add_header("X-Wyrdsekai-Admin-Token", tok)


def _zone_get(path: str, timeout: float = 30.0):
    req = urllib.request.Request(_zone_base() + path, method="GET")
    _admin_headers(req)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read().decode("utf-8"))
    except (urllib.error.URLError, OSError, ValueError, TimeoutError):
        return None


def _zone_post(path: str, body: dict | None, timeout: float = 30.0):
    data = json.dumps(body or {}).encode("utf-8")
    req = urllib.request.Request(_zone_base() + path, data=data, method="POST")
    req.add_header("Content-Type", "application/json")
    _admin_headers(req)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        try:
            return json.loads(e.read().decode("utf-8"))
        except Exception:
            return None
    except (urllib.error.URLError, OSError, ValueError, TimeoutError):
        return None


def _as_float(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def _stats_path(target_recipe: str, agent_did: str) -> str:
    q = {}
    if agent_did:
        q["agentDid"] = agent_did
    qs = ("?" + urllib.parse.urlencode(q)) if q else ""
    return f"/api/recipes/{urllib.parse.quote(target_recipe)}/tune/stats{qs}"


def _apply_path(target_recipe: str) -> str:
    return f"/api/recipes/{urllib.parse.quote(target_recipe)}/tune/apply"


def cmd_tune(args):
    allow = [t.strip().lower() for t in (args.tunable or "").split(",") if t.strip()]
    stats = _zone_get(_stats_path(args.target_recipe, args.agent_did))

    tuned, refused, skipped_floor = [], [], 0
    total_runs, fail_rate = 0, 0.0

    if isinstance(stats, dict):
        s = stats.get("stats") or {}
        total_runs = int(s.get("total", 0) or 0)
        fail_rate = float(s.get("failRate", 0.0) or 0.0)

        # Only act with enough evidence AND a real failure signal. Below the
        # threshold the recipe is a deliberate no-op (no thrashing on noise).
        act = total_runs >= args.min_runs and fail_rate >= args.max_fail_rate

        for p in stats.get("params", []) if act else []:
            if not isinstance(p, dict):
                continue
            name = p.get("name") or ""
            if p.get("floorProtected"):
                skipped_floor += 1
                continue
            # name must match the allowlist (substring); empty allowlist → none.
            lname = name.lower()
            if not any(tok in lname for tok in allow):
                continue
            base = _as_float(p.get("manifestDefault"))
            cur = _as_float(p.get("effective"))
            if base is None or base <= 0:
                continue
            if cur is None:
                cur = base
            ceiling = base * args.abs_max_mult
            target = cur * (1.0 + args.nudge_frac)
            target = min(target, ceiling)
            # Already at/over the ceiling → nothing useful to do.
            if target <= cur + 1e-9:
                continue
            resp = _zone_post(_apply_path(args.target_recipe), {
                "param": name,
                "value": target,
                # envelope: never below the manifest default, never above ceiling.
                "min": base,
                "max": ceiling,
                "agentDid": args.agent_did or "",
                "updatedBy": "tune-recipe-params",
            })
            if isinstance(resp, dict) and resp.get("applied"):
                tuned.append({"param": name, "from": cur, "to": target})
            else:
                reason = (resp or {}).get("refusal", "no_response")
                refused.append({"param": name, "reason": reason})

    state = {
        "target_recipe": args.target_recipe,
        "agent_did": args.agent_did,
        "total_runs": total_runs,
        "fail_rate": round(fail_rate, 4),
        "tuned_count": len(tuned),
        "refused_count": len(refused),
        "skipped_floor_count": skipped_floor,
        "tuned_params": tuned,
        "min_runs": args.min_runs,
        "stats_reachable": 1 if isinstance(stats, dict) else 0,
    }
    _state_path(args.target_recipe, args.agent_did).write_text(json.dumps(state))
    print(json.dumps({
        "target_recipe": args.target_recipe,
        "total_runs": total_runs,
        "fail_rate": state["fail_rate"],
        "tuned_count": len(tuned),
        "refused_count": len(refused),
        "skipped_floor_count": skipped_floor,
        "stats_reachable": state["stats_reachable"],
    }))


def cmd_report(args):
    state = {}
    try:
        state = json.loads(_state_path(args.target_recipe, args.agent_did).read_text())
    except (OSError, json.JSONDecodeError):
        pass
    tuned = int(state.get("tuned_count", 0))
    recs = []
    if tuned > 0:
        # Advisory — the cadence/steward decides whether to keep watching. A
        # tuned param should be re-measured over the next window.
        recs.append("re-measure-tuned-recipe-next-window")
    report = {
        "tuned_ok": 1 if tuned > 0 else 0,
        "target_recipe": state.get("target_recipe", args.target_recipe),
        "total_runs": int(state.get("total_runs", 0)),
        "fail_rate": float(state.get("fail_rate", 0.0)),
        "tuned_count": tuned,
        "refused_count": int(state.get("refused_count", 0)),
        "skipped_floor_count": int(state.get("skipped_floor_count", 0)),
        "tuned_params": state.get("tuned_params", []),
        "recommendations": recs,
        "agent_did": args.agent_did,
    }
    try:
        _state_path(args.target_recipe, args.agent_did).unlink()
    except FileNotFoundError:
        pass
    print(json.dumps(report))


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)

    def common(p):
        p.add_argument("--target-recipe", required=True)
        p.add_argument("--agent-did", default="")

    p_t = sub.add_parser("tune"); common(p_t)
    p_t.add_argument("--max-fail-rate", type=float, default=0.25)
    p_t.add_argument("--nudge-frac", type=float, default=0.25)
    p_t.add_argument("--abs-max-mult", type=float, default=4.0)
    p_t.add_argument("--min-runs", type=int, default=5)
    # Substring allowlist of param names the tuner may touch (comma-separated).
    p_t.add_argument("--tunable",
                     default="timeout,lookback,window,days,minutes,seconds,retry,limit")
    p_r = sub.add_parser("report"); common(p_r)

    args = ap.parse_args()
    {"tune": cmd_tune, "report": cmd_report}[args.cmd](args)


if __name__ == "__main__":
    main()
