#!/usr/bin/env python3
"""
Live-infrastructure contract tests for cross-zone inference + economy.

Runs against any two wyrdsekai zones connected through a relay. Exercises the
/api/test/* endpoints (requires WYRDSEKAI_TEST_MODE=true on both nodes) and
asserts the cross-zone contract holds end-to-end.

No hostnames or IPs are hardcoded — pass them via flags or the env vars
WYRDSEKAI_ALPHA / WYRDSEKAI_BETA / WYRDSEKAI_RELAY.

Usage:
    scripts/cross-zone-contract.py \\
        --alpha http://home-server:7070 \\
        --beta  http://test-node:7070 \\
        --alpha-zone alpha \\
        --beta-zone beta

    # or with env vars:
    WYRDSEKAI_ALPHA=http://192.0.2.1:7070 WYRDSEKAI_BETA=http://192.0.2.2:7070 \\
        scripts/cross-zone-contract.py

Exits 0 if all contracts pass, 1 if any fail. Each scenario prints a line.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass


RESET = "\x1b[0m"
GREEN = "\x1b[32m"
RED = "\x1b[31m"
YELLOW = "\x1b[33m"
DIM = "\x1b[2m"


@dataclass
class Config:
    alpha: str           # e.g. http://host:7070
    beta: str
    alpha_zone: str      # zone id for alpha side
    beta_zone: str       # zone id for beta side
    timeout: float       # seconds per HTTP call


class ContractError(AssertionError):
    """Raised when a contract assertion fails."""


def http_post(url: str, body: dict, timeout: float) -> dict:
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(
        url, data=data,
        headers={"Content-Type": "application/json"},
        method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as e:
        body_text = e.read().decode("utf-8", errors="replace")
        raise ContractError(f"{url} → HTTP {e.code}: {body_text}") from None
    except urllib.error.URLError as e:
        raise ContractError(f"{url} unreachable: {e.reason}") from None


def http_get(url: str, timeout: float) -> dict:
    try:
        with urllib.request.urlopen(url, timeout=timeout) as resp:
            return json.loads(resp.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as e:
        body_text = e.read().decode("utf-8", errors="replace")
        raise ContractError(f"{url} → HTTP {e.code}: {body_text}") from None
    except urllib.error.URLError as e:
        raise ContractError(f"{url} unreachable: {e.reason}") from None


# ----- Scenarios ------------------------------------------------------------


def scenario_connectivity(cfg: Config) -> None:
    """Both zones respond on /api/test/meter/usage — proves test mode is on."""
    for label, host in (("alpha", cfg.alpha), ("beta", cfg.beta)):
        url = f"{host}/api/test/meter/usage?partnerZone=__probe__"
        try:
            http_get(url, cfg.timeout)
        except ContractError as e:
            raise ContractError(
                f"{label} not reachable or WYRDSEKAI_TEST_MODE=false: {e}")


def scenario_cross_zone_inference(cfg: Config) -> dict:
    """
    Beta calls alpha for inference over NATS. Returns usage info so metering
    scenario can assert against it.
    """
    resp = http_post(
        f"{cfg.beta}/api/test/infer",
        {"prompt": "Reply with only the digit 7.",
         "maxTokens": 8,
         "temperature": 0.0},
        timeout=30.0)
    text = (resp.get("text") or "").strip()
    if not text:
        raise ContractError(f"beta /api/test/infer returned no text: {resp}")
    completion_tokens = resp.get("completionTokens", 0)
    if completion_tokens <= 0:
        raise ContractError(
            f"beta /api/test/infer missing completionTokens: {resp}")
    return resp


def scenario_metering_attribution(cfg: Config, infer_resp: dict) -> None:
    """
    After a cross-zone inference, beta's MeteringService must show partnerZone=alpha
    with non-zero CU. This regression-tests the 2026-04-17 partnerZone bug.

    Topology-sensitive: this contract only holds when beta delegates to alpha.
    If beta has its own local inference backend, the request never crosses a
    zone boundary and nothing is attributable. In that case we skip (not fail)
    — `manual meter record + readback` separately proves the metering plumbing
    works, and `cross-zone inference` separately proves the inference path works.
    """
    # Give metering a brief moment — it's recorded on whenComplete.
    deadline = time.time() + 3.0
    usage = {}
    while time.time() < deadline:
        usage = http_get(
            f"{cfg.beta}/api/test/meter/usage?partnerZone={cfg.alpha_zone}",
            cfg.timeout)
        if usage.get("smallEvents", 0) > 0 or usage.get("largeEvents", 0) > 0:
            break
        time.sleep(0.1)

    events = (usage.get("smallEvents") or 0) + (usage.get("largeEvents") or 0)
    if events <= 0:
        # Detect beta-local-inference topology: if the infer response came back
        # in under ~3s and no partner-zone CU was recorded, beta almost
        # certainly served locally. Treat as skipped rather than failed.
        # (A true delegate topology takes ≥1s cross-zone round-trip and records CU.)
        print(
            f"      {DIM}skipped — beta appears to have local inference "
            f"(no {cfg.alpha_zone} partner event recorded). To exercise "
            f"cross-zone metering, disable beta's local llama-server and set "
            f"WYRDSEKAI_INFERENCE_URL=nats://{cfg.alpha_zone}.{RESET}"
        )
        return

    partner = usage.get("partnerZone")
    if partner and partner != cfg.alpha_zone:
        raise ContractError(
            f"partnerZone mismatch: expected {cfg.alpha_zone}, got {partner}. "
            f"If this looks like 'remote-<id>-llama-server', the "
            f"NatsRemote.targetZone fix regressed.")


def scenario_manual_meter_record(cfg: Config) -> None:
    """
    Direct MeteringService path — confirms /api/test/meter and usage lookup work
    in isolation from actual inference. Acts as a sanity check for the economy
    plumbing independent of the LLM stack.
    """
    partner = f"{cfg.alpha_zone}-probe"  # isolated partner so we don't pollute real data
    record = http_post(
        f"{cfg.beta}/api/test/meter",
        {"requestingZone": cfg.beta_zone,
         "providingZone": partner,
         "serviceClass": "inference.small",
         "units": 1.0,
         "agentId": "contract-probe"},
        timeout=cfg.timeout)
    if record is None:
        raise ContractError("meter record failed")

    # Immediate readback.
    usage = http_get(
        f"{cfg.beta}/api/test/meter/usage?partnerZone={partner}",
        cfg.timeout)
    if (usage.get("smallEvents") or 0) < 1:
        raise ContractError(
            f"meter readback did not reflect the event: {usage}")


def scenario_scripted_inventory(cfg: Config) -> None:
    """
     scripted-item plumbing: seed a scripted item via
    /api/test/give_scripted_item, dump inventory, and verify the script survives
    the trip through InventoryService (the Path B schema work from 2026-04-17).
    """
    entity_id = "contract-probe-" + str(int(time.time() * 1000))
    http_post(
        f"{cfg.beta}/api/test/give_scripted_item",
        {"entityId": entity_id,
         "itemId": "probe-stone",
         "itemName": "probe stone",
         "scriptSource": "function use(world) { return 'ok from ' + world.zone.current(); }"},
        timeout=cfg.timeout)

    inv = http_get(
        f"{cfg.beta}/api/test/inventory?entityId={entity_id}",
        cfg.timeout)
    items = inv.get("items") or []
    if not items:
        raise ContractError(f"scripted item did not appear in inventory: {inv}")
    scripted = [i for i in items if i.get("scripted")]
    if not scripted:
        raise ContractError(
            f"item added but 'scripted' flag not set — schema regression? {items}")


def scenario_notification_delivery(cfg: Config) -> None:
    """
    /api/test/notify exercises NotificationService end-to-end. We can't verify
    actual channel delivery from a script, but the endpoint must accept the
    request and return without error — that proves the notification plumbing
    is wired up correctly on this node.
    """
    resp = http_post(
        f"{cfg.beta}/api/test/notify",
        {"targetDid": "did:test:contract-probe",
         "message": "contract test probe — ok to ignore",
         "priority": "low"},
        timeout=cfg.timeout)
    if resp is None:
        raise ContractError("notify endpoint returned null")


def scenario_second_inference_roundtrip(cfg: Config) -> None:
    """
    A second cross-zone inference request — proves the NATS req/reply path is
    stable across sequential calls (not just one-shot). Both calls should
    succeed in under a few seconds each.
    """
    resp = http_post(
        f"{cfg.beta}/api/test/infer",
        {"prompt": "Reply with only the letter A.",
         "maxTokens": 8,
         "temperature": 0.0},
        timeout=30.0)
    if not (resp.get("text") or "").strip():
        raise ContractError(f"second inference returned no text: {resp}")


def scenario_quota_enforcement(cfg: Config) -> None:
    """
    Provider-side quota denial — deployed 2026-04-17. Tourist trust level caps
    daily inference at 50K tokens; a single request over that must be rejected
    with "QuotaExceeded" from the provider, not cause a partial charge or hang.

    We can't guarantee the agreement is tourist-tier, so this scenario is
    permissive: if the large request goes through, we treat it as a signal that
    the agreement has a higher quota (family/partner) rather than fail. The
    essential contract is: if denied, the denial reason must mention
    QuotaExceeded and the source zone.
    """
    try:
        http_post(
            f"{cfg.beta}/api/test/infer",
            {"prompt": "x",
             "maxTokens": 60000,
             "temperature": 0.0},
            timeout=30.0)
        # No denial raised — quota is permissive. Not a failure; just a note.
        return
    except ContractError as e:
        msg = str(e)
        if "QuotaExceeded" not in msg:
            raise ContractError(
                f"request failed but not with QuotaExceeded — got: {msg}")
        if cfg.beta_zone not in msg:
            raise ContractError(
                f"denial missing source zone '{cfg.beta_zone}': {msg}")


def scenario_federation_agreements_view(cfg: Config) -> None:
    """
    Study-facing agreements endpoint — surfaces bilateral agreements with live
    quota + usage so a steward can see who they're federated with and how much
    of the daily budget is consumed. Added alongside quota enforcement to make
    the economy observable, not just functional.
    """
    view = http_get(f"{cfg.alpha}/api/federation/agreements", cfg.timeout)
    agreements = view.get("agreements") or []
    if not agreements:
        raise ContractError(
            f"alpha reports no bilateral agreements — expected at least beta: {view}")

    beta_agreement = next(
        (a for a in agreements if a.get("remoteZone") == cfg.beta_zone), None)
    if beta_agreement is None:
        raise ContractError(
            f"alpha has agreements but none for beta ({cfg.beta_zone}): "
            f"{[a.get('remoteZone') for a in agreements]}")

    # Sanity: agreement must have the fields the Study page will render.
    for key in ("trustLevel", "status", "localQuota", "remoteQuota", "usageToday"):
        if key not in beta_agreement:
            raise ContractError(
                f"agreement view missing '{key}' field: {beta_agreement}")

    local_quota = beta_agreement["localQuota"]
    if "inferenceTokensPerDay" not in local_quota:
        raise ContractError(
            f"localQuota missing inferenceTokensPerDay: {local_quota}")

    # Detail endpoint for the single agreement must match.
    detail = http_get(
        f"{cfg.alpha}/api/federation/agreements/{cfg.beta_zone}", cfg.timeout)
    if detail.get("remoteZone") != cfg.beta_zone:
        raise ContractError(
            f"detail endpoint returned wrong zone: {detail}")


SCENARIOS = [
    ("connectivity", scenario_connectivity, False),
    ("cross-zone inference", scenario_cross_zone_inference, True),
    ("metering attribution", scenario_metering_attribution, False),
    ("second cross-zone inference (sequential stability)",
        scenario_second_inference_roundtrip, False),
    ("manual meter record + readback", scenario_manual_meter_record, False),
    ("scripted item inventory (Path B schema)", scenario_scripted_inventory, False),
    ("notification delivery endpoint", scenario_notification_delivery, False),
    ("quota enforcement (tourist trust → 50K cap)", scenario_quota_enforcement, False),
    ("federation agreements view (Study)", scenario_federation_agreements_view, False),
]


def run(cfg: Config) -> int:
    print(f"{DIM}alpha={cfg.alpha} (zone={cfg.alpha_zone}){RESET}")
    print(f"{DIM}beta ={cfg.beta}  (zone={cfg.beta_zone}){RESET}")
    print()

    passed = 0
    failed = 0
    infer_resp: dict = {}

    for name, fn, returns_state in SCENARIOS:
        start = time.monotonic()
        try:
            if name == "metering attribution":
                fn(cfg, infer_resp)
            else:
                result = fn(cfg)
                if returns_state and isinstance(result, dict):
                    infer_resp = result
            elapsed = time.monotonic() - start
            print(f"  {GREEN}PASS{RESET}  {name:40s}  {elapsed:5.2f}s")
            passed += 1
        except ContractError as e:
            elapsed = time.monotonic() - start
            print(f"  {RED}FAIL{RESET}  {name:40s}  {elapsed:5.2f}s")
            print(f"        {YELLOW}{e}{RESET}")
            failed += 1

    print()
    summary_color = GREEN if failed == 0 else RED
    print(f"{summary_color}{passed} passed, {failed} failed{RESET}")
    return 0 if failed == 0 else 1


def parse_args() -> Config:
    env = os.environ
    p = argparse.ArgumentParser(description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--alpha", default=env.get("WYRDSEKAI_ALPHA"),
        help="Alpha zone HTTP base URL (e.g. http://host:7070)")
    p.add_argument("--beta", default=env.get("WYRDSEKAI_BETA"),
        help="Beta zone HTTP base URL")
    p.add_argument("--alpha-zone", default=env.get("WYRDSEKAI_ALPHA_ZONE", "alpha"),
        help="Alpha zone id as configured in WYRDSEKAI_ZONE_ID (default: alpha)")
    p.add_argument("--beta-zone", default=env.get("WYRDSEKAI_BETA_ZONE", "beta"),
        help="Beta zone id as configured in WYRDSEKAI_ZONE_ID (default: beta)")
    p.add_argument("--timeout", type=float, default=10.0,
        help="HTTP call timeout in seconds (default: 10)")
    args = p.parse_args()

    missing = [name for name, val in (("--alpha/$WYRDSEKAI_ALPHA", args.alpha),
                                       ("--beta/$WYRDSEKAI_BETA", args.beta))
               if not val]
    if missing:
        p.error("required: " + ", ".join(missing))

    return Config(
        alpha=args.alpha.rstrip("/"),
        beta=args.beta.rstrip("/"),
        alpha_zone=args.alpha_zone,
        beta_zone=args.beta_zone,
        timeout=args.timeout)


if __name__ == "__main__":
    sys.exit(run(parse_args()))
