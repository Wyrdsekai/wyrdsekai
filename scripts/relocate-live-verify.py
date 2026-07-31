#!/usr/bin/env python3
"""
Live-verify the cross-zone companion relocation path on a deployed mesh.
.

Three phases:
  1. preflight  — both zones reachable, services running, federation active
  2. watch      — tail logs on both ends in parallel; await the relocate sequence
  3. verify     — assert all expected log lines appeared in the right order

Trigger the cross-zone follow yourself (via SSH MUD client in another terminal)
while the script watches. The script does NOT mutate state — it only reads
service status, logs, and (optionally) /api/test endpoints.

Typical use:
    scripts/relocate-live-verify.py --alpha home-server --beta test-node

If alpha/beta run from source rather than .deb, override the log command:
    scripts/relocate-live-verify.py --alpha home-server --beta test-node \\
        --alpha-log-cmd 'tail -F /home/you/src/wyrdsekai/server.log' \\
        --beta-log-cmd  'journalctl -u wyrdsekai --no-pager'

Exits 0 on full success, 1 if preflight or watch fails. Each step prints a
clear pass/fail line; on failure the matched + missing patterns are dumped
along with a tail of recent log lines for diagnosis.
"""

from __future__ import annotations

import argparse
import os
import re
import shlex
import subprocess
import sys
import threading
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Callable, List, Optional

RESET = "\x1b[0m"
GREEN = "\x1b[32m"
RED = "\x1b[31m"
YELLOW = "\x1b[33m"
DIM = "\x1b[2m"

# Expected log fragments, in order, on each side. Anchored on stable
# log strings emitted by CompanionActor / FederationActor / ZoneGuardian
# in the cross-zone relocate path. If a log message text changes, update
# here AND in the matching test assertion.
ALPHA_EXPECTED = [
    r"fired RelocateDepart toward zone",
    r"published companion_relocate for agent",
    r"Companion '.+' stopping for relocate",
]
BETA_EXPECTED = [
    r"companion_relocate sink returned ok",
    r"Companion '.+' \(.+\) spawned in room",
    r"Companion '.+' marked as visiting; home zone is",
    r"Companion '.+' restored from transit",
]

DEFAULT_LOG_CMD = "journalctl -u wyrdsekai --no-pager -f --since '{since}'"


@dataclass
class Zone:
    label: str            # "alpha" / "beta"
    host: str             # ssh target
    log_cmd: str          # remote command that streams logs
    expected: List[str]
    matched: List[str] = field(default_factory=list)
    tail: List[str] = field(default_factory=list)  # last N lines for diagnosis
    proc: Optional[subprocess.Popen] = None


@dataclass
class Cfg:
    alpha: Zone
    beta: Zone
    ssh_user: str
    ssh_opts: List[str]
    watch_timeout: int
    preflight_only: bool
    api_alpha: Optional[str]
    api_beta: Optional[str]
    # --auto-trigger MUD driver
    auto_trigger: bool = False
    mud_host: Optional[str] = None
    mud_port: int = 2222
    mud_user: Optional[str] = None
    mud_pass: Optional[str] = None
    companion_name: str = "wyrd"
    target_zone_id: Optional[str] = None
    portal_cmd: Optional[str] = None  # default: 'say travel <target>'
    auto_delay_seconds: int = 4         # wait before triggering, lets watch attach


# ---------- helpers ----------

def ok(msg: str) -> None:
    print(f"{GREEN}✓{RESET} {msg}")

def fail(msg: str) -> None:
    print(f"{RED}✗{RESET} {msg}")

def info(msg: str) -> None:
    print(f"{DIM}·{RESET} {msg}")

def warn(msg: str) -> None:
    print(f"{YELLOW}!{RESET} {msg}")


def ssh_run(zone: Zone, ssh_user: str, ssh_opts: List[str], cmd: str,
            timeout: int = 10) -> tuple[int, str, str]:
    """Run a one-shot command via ssh on the zone's host."""
    full = ["ssh", *ssh_opts, f"{ssh_user}@{zone.host}", cmd]
    try:
        proc = subprocess.run(full, capture_output=True, text=True, timeout=timeout)
        return proc.returncode, proc.stdout.strip(), proc.stderr.strip()
    except subprocess.TimeoutExpired:
        return 124, "", f"ssh timeout after {timeout}s"


# ---------- phase 1: preflight ----------

def preflight(cfg: Cfg) -> bool:
    """Verify both zones are alive + federated. Best-effort — non-fatal warnings
    when the .deb-specific checks don't apply (e.g. source-mode deployments)."""
    print()
    print(f"{DIM}=== preflight ==={RESET}")
    all_ok = True

    for z in (cfg.alpha, cfg.beta):
        rc, out, err = ssh_run(z, cfg.ssh_user, cfg.ssh_opts, "hostname", timeout=5)
        if rc != 0:
            fail(f"{z.label}: ssh {cfg.ssh_user}@{z.host} failed: {err or 'rc=' + str(rc)}")
            all_ok = False
            continue
        ok(f"{z.label}: ssh ok ({out})")

        # Service running? Try systemd first, fall back to a process search.
        rc, out, _ = ssh_run(z, cfg.ssh_user, cfg.ssh_opts,
                              "systemctl is-active wyrdsekai 2>/dev/null || pgrep -f 'wyrdsekai|java.*Main' >/dev/null && echo running || echo missing",
                              timeout=5)
        status = (out or "").strip().splitlines()[-1] if out else ""
        if status in ("active", "running"):
            ok(f"{z.label}: wyrdsekai service is {status}")
        else:
            fail(f"{z.label}: wyrdsekai service appears {status or 'unknown'}")
            all_ok = False

        # /api/health if we know the URL
        api = cfg.api_alpha if z.label == "alpha" else cfg.api_beta
        if api:
            rc, out, err = ssh_run(z, cfg.ssh_user, cfg.ssh_opts,
                                    f"curl -fsS --max-time 4 {api}/health || true",
                                    timeout=8)
            if "UP" in out or '"status":"UP"' in out:
                ok(f"{z.label}: /health UP")
            else:
                warn(f"{z.label}: /health did not return UP "
                     f"(skipping; may be source-mode without HTTP)")

    # Federation agreement check — query each side's bilateral_agreements
    # via a small sqlite probe IF we can find the DB. Best-effort.
    if all_ok:
        for src, dst in ((cfg.alpha, cfg.beta), (cfg.beta, cfg.alpha)):
            probe = (
                "DB=$(ls -1 ~/.wyrdsekai/*.db 2>/dev/null | head -1); "
                "[ -n \"$DB\" ] && sqlite3 \"$DB\" "
                "\"SELECT status FROM bilateral_agreements WHERE remote_zone_id LIKE '%" + dst.host + "%' LIMIT 1\" 2>/dev/null || true"
            )
            rc, out, _ = ssh_run(src, cfg.ssh_user, cfg.ssh_opts, probe, timeout=5)
            if "active" in (out or ""):
                ok(f"{src.label}→{dst.label}: federation agreement active")
            else:
                warn(f"{src.label}→{dst.label}: agreement status unknown "
                     f"(probe inconclusive — verify via 'wyrd federate list' if relocate fails)")

    return all_ok


# ---------- phase 2: watch ----------

def stream_logs(cfg: Cfg, zone: Zone, since: str, stop_event: threading.Event,
                ready_event: threading.Event) -> None:
    """Stream remote logs over ssh, match expected patterns in order."""
    cmd = zone.log_cmd.format(since=since)
    full = ["ssh", *cfg.ssh_opts, f"{cfg.ssh_user}@{zone.host}", cmd]
    zone.proc = subprocess.Popen(
        full, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, bufsize=1)
    cursor = 0  # index into zone.expected
    assert zone.proc.stdout is not None
    for line in zone.proc.stdout:
        if stop_event.is_set():
            break
        line = line.rstrip()
        zone.tail.append(line)
        if len(zone.tail) > 200:
            zone.tail = zone.tail[-200:]
        if cursor < len(zone.expected):
            pattern = zone.expected[cursor]
            if re.search(pattern, line):
                zone.matched.append(line)
                print(f"  {GREEN}✓{RESET} [{zone.label}] {pattern}")
                cursor += 1
                if cursor == len(zone.expected):
                    ready_event.set()
    if zone.proc:
        try: zone.proc.terminate()
        except Exception: pass


def auto_trigger_thread(cfg: Cfg) -> None:
    """Drive the MUD interaction over SSH using pexpect.
    Lazily imports pexpect so the rest of the script works without it."""
    time.sleep(cfg.auto_delay_seconds)
    try:
        import pexpect
    except ImportError:
        warn("pexpect not installed — pip install pexpect to enable --auto-trigger")
        return
    host = cfg.mud_host or cfg.alpha.host
    port = cfg.mud_port
    target = cfg.target_zone_id or cfg.beta.host
    portal = cfg.portal_cmd or f"say travel {target}"
    info(f"auto-trigger: connecting to {cfg.mud_user}@{host}:{port}")
    cmd = f"ssh -p {port} -o StrictHostKeyChecking=accept-new {cfg.mud_user}@{host}"
    try:
        child = pexpect.spawn(cmd, timeout=15, encoding="utf-8")
        child.expect(["password:", "passcode:"])
        child.sendline(cfg.mud_pass or "")
        # Wait for any post-login prompt (Study room display, "you are in..." etc.)
        child.expect([r"\$\s*$", r">\s*$", r"You are .*", r"\bStudy\b"], timeout=15)
        info(f"auto-trigger: logged in, sending take-companion + transit commands")
        # 1. Issue take-companion / cross-zone invite
        child.sendline(f"tell {cfg.companion_name} take you with me to {target}")
        time.sleep(1)
        # 2. Trigger cross-zone transit
        child.sendline(portal)
        # Give the server a beat to process
        time.sleep(3)
        info("auto-trigger: commands sent; closing MUD session")
        try:
            child.sendline("quit")
        except Exception:
            pass
        try:
            child.close(force=True)
        except Exception:
            pass
    except Exception as e:
        fail(f"auto-trigger failed: {e}")


def watch(cfg: Cfg) -> bool:
    print()
    print(f"{DIM}=== watch ==={RESET}")
    since = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S")
    print(f"  watching since {since} UTC, timeout {cfg.watch_timeout}s")
    print()
    if cfg.auto_trigger:
        info(f"auto-trigger ENABLED (will fire {cfg.auto_delay_seconds}s after watch starts)")
    else:
        print(f"  {YELLOW}>>>{RESET} Trigger the cross-zone follow now (in another terminal):")
        print(f"      ssh -p 2222 <player>@{cfg.alpha.host}")
        print(f"      > tell wyrd take you with me to {cfg.beta.host}")
        print(f"      > say travel {cfg.beta.host}    # or 'go <portal-direction>'")
    print()

    stop_event = threading.Event()
    alpha_ready = threading.Event()
    beta_ready = threading.Event()

    threads = [
        threading.Thread(
            target=stream_logs, args=(cfg, cfg.alpha, since, stop_event, alpha_ready),
            daemon=True),
        threading.Thread(
            target=stream_logs, args=(cfg, cfg.beta, since, stop_event, beta_ready),
            daemon=True),
    ]
    if cfg.auto_trigger:
        threads.append(threading.Thread(
            target=auto_trigger_thread, args=(cfg,), daemon=True))
    for t in threads:
        t.start()

    # Wait for both sides to match all expected, OR timeout
    deadline = time.time() + cfg.watch_timeout
    while time.time() < deadline:
        if alpha_ready.is_set() and beta_ready.is_set():
            break
        time.sleep(0.5)

    stop_event.set()
    for z in (cfg.alpha, cfg.beta):
        if z.proc and z.proc.poll() is None:
            try: z.proc.terminate()
            except Exception: pass
    for t in threads:
        t.join(timeout=2)

    success = alpha_ready.is_set() and beta_ready.is_set()
    return success


# ---------- phase 3: report ----------

def report(cfg: Cfg, success: bool) -> int:
    print()
    print(f"{DIM}=== report ==={RESET}")
    rc = 0
    for z in (cfg.alpha, cfg.beta):
        matched = len(z.matched)
        total = len(z.expected)
        if matched == total:
            ok(f"{z.label}: {matched}/{total} expected log lines matched")
        else:
            fail(f"{z.label}: only {matched}/{total} expected log lines matched")
            missing = z.expected[matched:]
            for p in missing:
                print(f"      {RED}missing:{RESET} {p}")
            rc = 1
            if z.tail:
                print(f"      {DIM}last {min(20, len(z.tail))} log lines from {z.label}:{RESET}")
                for line in z.tail[-20:]:
                    print(f"        {DIM}{line}{RESET}")

    print()
    if rc == 0:
        ok("relocate path verified end-to-end on the live mesh")
    else:
        fail("relocate path did not complete — see missing patterns above")
        print(f"  hint: confirm WYRDSEKAI_ENVELOPE_VERIFY (off|soft|hard), federation")
        print(f"  agreement is active, and the test player has an active bond with Wyrd.")
    return rc


# ---------- main ----------

def parse_args() -> Cfg:
    ap = argparse.ArgumentParser(
        description="Live-verify cross-zone relocate.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__)
    ap.add_argument("--alpha", required=True, help="alpha zone host (ssh target)")
    ap.add_argument("--beta", required=True, help="beta zone host (ssh target)")
    ap.add_argument("--ssh-user", default=os.environ.get("USER", "operator"))
    ap.add_argument("--ssh-opts",
                     default="-o BatchMode=yes -o ConnectTimeout=5 -o StrictHostKeyChecking=accept-new",
                     help="extra ssh options (default: noninteractive)")
    ap.add_argument("--alpha-log-cmd", default=DEFAULT_LOG_CMD,
                     help="remote command to stream alpha's logs; "
                          "{since} is substituted with start timestamp")
    ap.add_argument("--beta-log-cmd", default=DEFAULT_LOG_CMD,
                     help="remote command to stream beta's logs (default: same as alpha)")
    ap.add_argument("--watch-timeout", type=int, default=60,
                     help="seconds to wait for the relocate sequence (default 60)")
    ap.add_argument("--preflight-only", action="store_true",
                     help="run preflight checks then exit")
    ap.add_argument("--api-alpha", help="alpha /health URL (e.g. http://home-server:7070)")
    ap.add_argument("--api-beta",  help="beta /health URL (e.g. http://test-node:7070)")
    # --auto-trigger MUD driver (requires pexpect)
    ap.add_argument("--auto-trigger", action="store_true",
                     help="drive the MUD interaction via SSH (requires pexpect)")
    ap.add_argument("--mud-host", help="MUD ssh host (default: alpha host)")
    ap.add_argument("--mud-port", type=int, default=2222, help="MUD ssh port (default 2222)")
    ap.add_argument("--mud-user", help="MUD test player username")
    ap.add_argument("--mud-pass", help="MUD test player password")
    ap.add_argument("--companion-name", default="wyrd",
                     help="companion name for the 'tell' command (default wyrd)")
    ap.add_argument("--target-zone-id", help="target zone id (default: beta host)")
    ap.add_argument("--portal-cmd", help="MUD command to trigger cross-zone transit "
                     "(default: 'say travel <target>')")
    args = ap.parse_args()

    if args.auto_trigger and not (args.mud_user and args.mud_pass):
        print("--auto-trigger requires --mud-user and --mud-pass", file=sys.stderr)
        sys.exit(2)

    return Cfg(
        alpha=Zone("alpha", args.alpha, args.alpha_log_cmd, ALPHA_EXPECTED),
        beta=Zone("beta", args.beta, args.beta_log_cmd, BETA_EXPECTED),
        ssh_user=args.ssh_user,
        ssh_opts=shlex.split(args.ssh_opts),
        watch_timeout=args.watch_timeout,
        preflight_only=args.preflight_only,
        api_alpha=args.api_alpha,
        api_beta=args.api_beta,
        auto_trigger=args.auto_trigger,
        mud_host=args.mud_host,
        mud_port=args.mud_port,
        mud_user=args.mud_user,
        mud_pass=args.mud_pass,
        companion_name=args.companion_name,
        target_zone_id=args.target_zone_id,
        portal_cmd=args.portal_cmd,
    )


def main() -> int:
    cfg = parse_args()
    print(f"alpha = {cfg.ssh_user}@{cfg.alpha.host}")
    print(f"beta  = {cfg.ssh_user}@{cfg.beta.host}")

    if not preflight(cfg):
        fail("preflight failed; aborting")
        return 1
    if cfg.preflight_only:
        ok("preflight only: done")
        return 0

    success = watch(cfg)
    return report(cfg, success)


if __name__ == "__main__":
    sys.exit(main())
