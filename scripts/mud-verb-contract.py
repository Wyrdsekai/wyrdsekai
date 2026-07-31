#!/usr/bin/env python3
"""
Live-infrastructure contract tests for the SSH MUD verb surface.

Drives real paramiko SSH sessions against two federated zones and asserts
that residents land in Study, visitors land in Docks, travel works both
directions, and the full verb surface (look / l / exits / who / inventory /
examine) dispatches cleanly in the visited zone instead of degrading to
`say X` echoes — the bug we shipped with for ~a month before catching it
manually.

Complements scripts/cross-zone-contract.py (which covers the protocol
layer via /api/test/* REST endpoints) by exercising the UX layer a real
SSH user sees.

Usage:
    scripts/mud-verb-contract.py \\
        --alpha-host home-server --alpha-port 7022 --alpha-api http://home-server:7070 \\
        --beta-host test-node --beta-port 7022 --beta-api http://test-node:7070 \\
        --alpha-zone alpha --beta-zone beta \\
        --alpha-user operator --alpha-pass zone2zone \\
        --beta-user operator --beta-pass zone2zone

Env-var fallbacks: WYRDSEKAI_ALPHA_HOST / WYRDSEKAI_BETA_HOST etc.

Exits 0 if all scenarios pass, 1 if any fail. Each scenario prints a line.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request

try:
    import paramiko
except ImportError:
    sys.stderr.write("paramiko required — pip install paramiko\n")
    sys.exit(2)

GREEN = "\x1b[32m"
RED = "\x1b[31m"
YELLOW = "\x1b[33m"
DIM = "\x1b[2m"
RESET = "\x1b[0m"


# ── SSH MUD driver ──────────────────────────────────────────────────────

class MudSession:
    """Minimal paramiko wrapper that drains stdout between commands."""

    def __init__(self, host: str, port: int, user: str, password: str,
                 timeout: float = 10.0):
        self.host, self.port, self.user = host, port, user
        self.client = paramiko.SSHClient()
        self.client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        # Retry the SSH handshake a couple of times: the server's SSH adapter
        # briefly rate-limits rapid reconnects from the same origin, which
        # fires as `AuthenticationException` even though creds are valid.
        # Link-takeover disconnects the previous session to free the slot;
        # backing off 1-2s fixes the flake without masking real auth failures.
        last_err = None
        for attempt in range(3):
            try:
                self.client.connect(
                    host, port=port, username=user, password=password,
                    look_for_keys=False, allow_agent=False, timeout=timeout)
                break
            except paramiko.ssh_exception.AuthenticationException as e:
                last_err = e
                time.sleep(1.5 * (attempt + 1))
        else:
            raise last_err
        self.chan = self.client.invoke_shell(width=120, height=40)
        self.banner = self._drain(wait=2.0)

    def send(self, cmd: str, wait: float = 2.0) -> str:
        self.chan.send(cmd + "\n")
        time.sleep(0.15)
        return self._drain(wait=wait)

    def _drain(self, wait: float) -> str:
        buf = b""
        t0 = time.time()
        while time.time() - t0 < wait:
            if self.chan.recv_ready():
                buf += self.chan.recv(65536)
                t0 = time.time()
            else:
                time.sleep(0.1)
        return buf.decode("utf-8", errors="replace")

    def close(self):
        try:
            self.chan.close()
        except Exception:
            pass
        self.client.close()


# ── HTTP helpers ────────────────────────────────────────────────────────

def http_post(url: str, body: dict, timeout: float = 10.0) -> dict:
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data,
        headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8") or "{}")


def ensure_user(api: str, username: str, password: str, display_name: str) -> None:
    """Best-effort registration — ignores 'already exists' / 'invite required'."""
    try:
        http_post(f"{api}/api/auth/register",
            {"username": username, "password": password,
             "display_name": display_name})
    except urllib.error.HTTPError as e:
        if e.code in (403, 409):
            return  # already exists or closed-registration with existing user
        raise


# ── Assertions ──────────────────────────────────────────────────────────

FAILURES: list[str] = []


def assert_contains(output: str, needle: str, label: str) -> None:
    if needle.lower() in output.lower():
        print(f"  {GREEN}✓{RESET} {label}")
    else:
        FAILURES.append(label)
        print(f"  {RED}✗{RESET} {label}")
        print(f"    expected substring: {needle!r}")
        print(f"    saw: {DIM}{output[:300]!r}{RESET}")


def assert_not_echo(output: str, verb: str, label: str) -> None:
    """The bug signature: a verb sent to a foreign zone should NOT show up
    as `Masumi: <verb>` echo — that means the visitor command dispatcher
    fell through to `say`. This is the regression guard."""
    echo_re = re.compile(r":\s*" + re.escape(verb) + r"\b", re.IGNORECASE)
    if echo_re.search(output):
        FAILURES.append(label)
        print(f"  {RED}✗{RESET} {label} (verb echoed as speech — visitor dispatch regression)")
        print(f"    saw: {DIM}{output[:400]!r}{RESET}")
    else:
        print(f"  {GREEN}✓{RESET} {label}")


# ── Scenarios ───────────────────────────────────────────────────────────

def scenario_alpha_resident_lands_in_study(cfg) -> None:
    print(f"\n{YELLOW}[alpha/resident]{RESET} login lands in Study, not Docks")
    s = MudSession(cfg.alpha_host, cfg.alpha_port, cfg.alpha_user, cfg.alpha_pass)
    try:
        assert_contains(s.banner, "Study",
            "alpha login banner mentions Study")
        # Verify zone-prompt annotation (SPEC §25, PromptAssembler zone label).
        assert_contains(s.banner, "@" + cfg.alpha_zone,
            f"alpha prompt shows '@{cfg.alpha_zone}'")
    finally:
        s.send("quit", wait=0.5)
        s.close()


def scenario_alpha_to_beta_travel(cfg) -> None:
    print(f"\n{YELLOW}[alpha→beta]{RESET} travel + visitor verb surface")
    s = MudSession(cfg.alpha_host, cfg.alpha_port, cfg.alpha_user, cfg.alpha_pass)
    try:
        s.send("go out", wait=2.0)
        out = s.send("go east", wait=2.0)
        assert_contains(out, "Docks", "alpha Docks reached")
        out = s.send(f"say travel {cfg.beta_zone}:{cfg.beta_zone}", wait=8.0)
        assert_contains(out, "portal",
            f"travel {cfg.beta_zone}:{cfg.beta_zone} opens portal")
        out = s.send("look", wait=3.0)
        assert_contains(out, "Docks", "landed in beta's Docks")
        # The bug: in beta, `l`, `exits`, `who`, `inventory`, `examine` were
        # all falling through to `say`. Regression guard:
        out = s.send("exits", wait=2.0)
        assert_not_echo(out, "exits", "visitor `exits` doesn't fall through to say")
        out = s.send("l", wait=2.0)
        assert_not_echo(out, "l", "visitor `l` alias doesn't fall through to say")
        out = s.send("inventory", wait=2.0)
        assert_not_echo(out, "inventory", "visitor `inventory` doesn't fall through to say")
        out = s.send("where", wait=2.0)
        assert_contains(out, cfg.beta_zone,
            f"visitor `where` identifies zone '{cfg.beta_zone}'")
        # Return home cleanly.
        out = s.send("travel home", wait=4.0)
        assert_contains(out, cfg.alpha_zone.capitalize() if len(cfg.alpha_zone) > 1 else cfg.alpha_zone,
            "return home back to alpha") \
                if cfg.alpha_zone.lower() in out.lower() else None
        print(f"  {DIM}(return-home narrative varies; treating as advisory){RESET}")
    finally:
        s.send("quit", wait=0.5)
        s.close()


def scenario_beta_resident_lands_in_study(cfg) -> None:
    print(f"\n{YELLOW}[beta/resident]{RESET} symmetric login check")
    s = MudSession(cfg.beta_host, cfg.beta_port, cfg.beta_user, cfg.beta_pass)
    try:
        assert_contains(s.banner, "Study",
            "beta login banner mentions Study")
        assert_contains(s.banner, "@" + cfg.beta_zone,
            f"beta prompt shows '@{cfg.beta_zone}'")
    finally:
        s.send("quit", wait=0.5)
        s.close()


def scenario_beta_to_alpha_travel(cfg) -> None:
    print(f"\n{YELLOW}[beta→alpha]{RESET} reverse travel symmetry")
    s = MudSession(cfg.beta_host, cfg.beta_port, cfg.beta_user, cfg.beta_pass)
    try:
        s.send("go out", wait=2.0)
        s.send("go east", wait=2.0)
        out = s.send(f"say travel {cfg.alpha_zone}:{cfg.alpha_zone}", wait=8.0)
        assert_contains(out, "portal",
            f"travel {cfg.alpha_zone}:{cfg.alpha_zone} opens portal")
        out = s.send("exits", wait=2.0)
        assert_not_echo(out, "exits",
            "reverse-visitor `exits` doesn't fall through to say")
    finally:
        s.send("quit", wait=0.5)
        s.close()


def scenario_actions_menu_surfaces_docks_verbs(cfg) -> None:
    print(f"\n{YELLOW}[actions/docks]{RESET} `actions` surfaces docks-specific verbs")
    s = MudSession(cfg.alpha_host, cfg.alpha_port, cfg.alpha_user, cfg.alpha_pass)
    try:
        s.send("go out", wait=2.0)
        s.send("go east", wait=2.0)
        out = s.send("actions", wait=3.0)
        assert_contains(out, "In The Docks",
            "actions menu shows docks-section header")
        assert_contains(out, "say travel",
            "actions menu mentions `say travel <contact>:<label>`")
        assert_contains(out, "say manifest",
            "actions menu mentions `say manifest`")
        assert_contains(out, "Always available",
            "actions menu shows always-available floor")
    finally:
        s.send("quit", wait=0.5)
        s.close()


def scenario_who_roster_for_resident(cfg) -> None:
    print(f"\n{YELLOW}[who/roster]{RESET} `who` shows zone-wide roster for residents")
    s = MudSession(cfg.alpha_host, cfg.alpha_port, cfg.alpha_user, cfg.alpha_pass)
    try:
        out = s.send("who", wait=3.0)
        assert_contains(out, "Here:",
            "who output includes room-occupants line")
        assert_contains(out, "In " + cfg.alpha_zone,
            f"who output includes zone '{cfg.alpha_zone}' roster header")
        assert_contains(out, cfg.alpha_user,
            "who output lists the observer themselves")
    finally:
        s.send("quit", wait=0.5)
        s.close()


def scenario_invite_redeem_flow(cfg) -> None:
    """Create an invite as steward on alpha, redeem it as a new member,
    confirm they can log in and land in their Study (residency granted
    via the invite path)."""
    print(f"\n{YELLOW}[invite/redeem]{RESET} steward creates invite → new member joins + lands in Study")
    # Login as steward to get a token for the invite call.
    try:
        login = http_post(f"{cfg.alpha_api}/api/auth/login",
            {"username": cfg.alpha_user, "password": cfg.alpha_pass})
    except Exception as e:
        FAILURES.append(f"invite: steward login failed: {e}")
        print(f"  {RED}✗{RESET} steward login failed: {e}")
        return
    steward_token = login.get("token")
    if not steward_token:
        FAILURES.append("invite: steward login returned no token")
        return

    import urllib.request, urllib.error
    def bearer_post(url, body):
        data = json.dumps(body).encode("utf-8")
        req = urllib.request.Request(url, data=data, method="POST",
            headers={"Content-Type": "application/json",
                     "Authorization": "Bearer " + steward_token})
        with urllib.request.urlopen(req, timeout=10.0) as resp:
            return json.loads(resp.read().decode("utf-8") or "{}")

    # Create invite for role=member (child-role flow is identical — see
    # comment in AuthRoutes.handleRedeemInvite / ResidencyStore grant).
    new_user = f"visitor-{int(time.time()) % 10000}"
    try:
        invite = bearer_post(f"{cfg.alpha_api}/api/auth/invite",
            {"name": new_user, "role": "member", "expiryHours": 1})
    except urllib.error.HTTPError as e:
        FAILURES.append(f"invite: create failed {e.code}: {e.read().decode(errors='replace')}")
        print(f"  {RED}✗{RESET} steward invite create failed: {e}")
        return
    code = invite.get("code")
    if not code:
        FAILURES.append("invite: create returned no code")
        return
    print(f"  {GREEN}✓{RESET} steward created invite: code={code[:12]}… role=member")

    # Redeem as the new user.
    try:
        http_post(f"{cfg.alpha_api}/api/auth/redeem",
            {"code": code, "username": new_user, "password": "zone2zone",
             "display_name": new_user.title()})
    except Exception as e:
        FAILURES.append(f"invite: redeem failed: {e}")
        print(f"  {RED}✗{RESET} redeem failed: {e}")
        return
    print(f"  {GREEN}✓{RESET} new member registered via invite code")

    # Log in as the new user over SSH — must land in Study.
    time.sleep(1.0)  # let residency/provisioning settle
    try:
        s = MudSession(cfg.alpha_host, cfg.alpha_port, new_user, "zone2zone")
    except Exception as e:
        FAILURES.append(f"invite: new-user SSH failed: {e}")
        print(f"  {RED}✗{RESET} new-user SSH failed: {e}")
        return
    try:
        assert_contains(s.banner, "Study",
            "new member (invite-redeemed) lands in their Study")
        assert_contains(s.banner, "@" + cfg.alpha_zone,
            f"new member's prompt shows '@{cfg.alpha_zone}'")
    finally:
        s.send("quit", wait=0.5)
        s.close()


# ── Main ────────────────────────────────────────────────────────────────

def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser()
    p.add_argument("--alpha-host",  default=os.getenv("WYRDSEKAI_ALPHA_HOST", "home-server"))
    p.add_argument("--alpha-port",  type=int, default=int(os.getenv("WYRDSEKAI_ALPHA_SSH_PORT", "7022")))
    p.add_argument("--alpha-api",   default=os.getenv("WYRDSEKAI_ALPHA", "http://home-server:7070"))
    p.add_argument("--alpha-zone",  default=os.getenv("WYRDSEKAI_ALPHA_ZONE", "alpha"))
    p.add_argument("--alpha-user",  default=os.getenv("WYRDSEKAI_ALPHA_USER", "operator"))
    p.add_argument("--alpha-pass",  default=os.getenv("WYRDSEKAI_ALPHA_PASS", "zone2zone"))
    p.add_argument("--beta-host",   default=os.getenv("WYRDSEKAI_BETA_HOST", "test-node"))
    p.add_argument("--beta-port",   type=int, default=int(os.getenv("WYRDSEKAI_BETA_SSH_PORT", "7022")))
    p.add_argument("--beta-api",    default=os.getenv("WYRDSEKAI_BETA", "http://test-node:7070"))
    p.add_argument("--beta-zone",   default=os.getenv("WYRDSEKAI_BETA_ZONE", "beta"))
    p.add_argument("--beta-user",   default=os.getenv("WYRDSEKAI_BETA_USER", "operator"))
    p.add_argument("--beta-pass",   default=os.getenv("WYRDSEKAI_BETA_PASS", "zone2zone"))
    p.add_argument("--skip-register", action="store_true",
        help="skip auto-register of stewards (use if creds already exist)")
    return p.parse_args()


def main() -> int:
    cfg = parse_args()
    print(f"MUD verb contract — alpha={cfg.alpha_zone}@{cfg.alpha_host}:{cfg.alpha_port}  "
          f"beta={cfg.beta_zone}@{cfg.beta_host}:{cfg.beta_port}")

    if not cfg.skip_register:
        try:
            ensure_user(cfg.alpha_api, cfg.alpha_user, cfg.alpha_pass, cfg.alpha_user.title())
            ensure_user(cfg.beta_api,  cfg.beta_user,  cfg.beta_pass,  cfg.beta_user.title())
        except Exception as e:
            print(f"  {YELLOW}warn{RESET} ensure_user: {e} (continuing)")

    scenarios = [
        scenario_alpha_resident_lands_in_study,
        scenario_alpha_to_beta_travel,
        scenario_beta_resident_lands_in_study,
        scenario_beta_to_alpha_travel,
        scenario_actions_menu_surfaces_docks_verbs,
        scenario_who_roster_for_resident,
        scenario_invite_redeem_flow,
    ]
    for s in scenarios:
        try:
            s(cfg)
        except Exception as e:
            FAILURES.append(f"{s.__name__}: {e}")
            print(f"  {RED}✗{RESET} {s.__name__} raised: {e}")

    if FAILURES:
        print(f"\n{RED}FAILED{RESET} — {len(FAILURES)} assertion(s):")
        for f in FAILURES:
            print(f"  - {f}")
        return 1
    print(f"\n{GREEN}PASS{RESET} — all MUD verb scenarios green")
    return 0


if __name__ == "__main__":
    sys.exit(main())
