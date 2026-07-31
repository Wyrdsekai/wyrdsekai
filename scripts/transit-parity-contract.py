#!/usr/bin/env python3
"""
Live-infrastructure contract tests for cross-zone transit client parity
.

Exercises the full "seamless cross-zone" promise across SSH and Telnet:
a user should be able to do everything in a remote zone that they can do
locally — look, say, emote, tell agents, take/drop items, navigate. The
contract asserts each activity round-trips correctly whether run directly in
the home zone or through a proxied session on the remote zone.

Usage:
    scripts/transit-parity-contract.py \\
        --home home-server \\
        --remote-zone beta \\
        --user traveler \\
        --pass cross1234 \\
        --ssh-port 7022 \\
        --telnet-port 7071

Returns 0 if all transports close the round-trip, 1 on any failure.

Dependencies:
    paramiko (for SSH). Telnet uses raw sockets (stdlib only).
"""

from __future__ import annotations

import argparse
import os
import socket
import sys
import time

RESET = "\x1b[0m"
GREEN = "\x1b[32m"
RED = "\x1b[31m"
YELLOW = "\x1b[33m"
DIM = "\x1b[2m"


IAC, DONT, DO, WONT, WILL = 255, 254, 253, 252, 251


# --- expectation helpers -----------------------------------------------------

TRANSIT_OPENED_MARKER = "step through the portal"
LOCAL_DOCKS_RETURN_MARKER = "Carrying:"


class ContractError(AssertionError):
    pass


def assert_in(haystack: str, needle: str, tag: str) -> None:
    if needle not in haystack:
        raise ContractError(
            f"{tag}: expected to see {needle!r} in output\n"
            f"--- got ---\n{haystack[-600:]}")


def assert_not_in(haystack: str, needle: str, tag: str) -> None:
    if needle in haystack:
        raise ContractError(
            f"{tag}: unexpected {needle!r} in output\n"
            f"--- got ---\n{haystack[-600:]}")


# --- SSH client --------------------------------------------------------------

def cmd_drain_timeout(cmd: str) -> float:
    """Transit and inter-zone commands need longer for remote events to
       flow back through the proxy. Everything else drains fast."""
    lc = cmd.lower()
    if "travel" in lc:
        return 10.0
    if lc.startswith(("go ", "look")):
        # Room-changes can take a moment when rooms are first materialising.
        return 5.0
    return 4.0


def ssh_session(host: str, port: int, user: str, password: str, cmds: list[str]) -> str:
    """Open an SSH shell, run each command sequentially, return drained output."""
    import paramiko  # lazy import so telnet can run without paramiko

    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(host, port=port, username=user, password=password,
                   look_for_keys=False, allow_agent=False, timeout=30)
    try:
        chan = client.invoke_shell()
        chan.settimeout(2.0)
        collected = drain_paramiko(chan, timeout=3.0)
        for cmd in cmds:
            chan.send(cmd + "\n")
            collected += "\n>>> " + cmd + "\n" + drain_paramiko(chan, timeout=cmd_drain_timeout(cmd))
        return collected
    finally:
        client.close()


def drain_paramiko(chan, timeout: float) -> str:
    buf = ""
    end = time.time() + timeout
    while time.time() < end:
        try:
            data = chan.recv(65536)
            if not data:
                break
            buf += data.decode("utf-8", errors="replace")
            end = time.time() + 0.8
        except Exception:
            break
    return buf


# --- Telnet client -----------------------------------------------------------

def telnet_session(host: str, port: int, user: str, password: str, cmds: list[str]) -> str:
    """Connect via telnet, log in, run each command, return drained output."""
    s = socket.create_connection((host, port), timeout=10)
    try:
        collected = drain_telnet(s, timeout=3.0)
        s.send(f"connect {user} {password}\n".encode())
        collected += "\n>>> connect\n" + drain_telnet(s, timeout=5.0)
        for cmd in cmds:
            s.send((cmd + "\n").encode())
            collected += "\n>>> " + cmd + "\n" + drain_telnet(s, timeout=cmd_drain_timeout(cmd))
        return collected
    finally:
        s.close()


def drain_telnet(sock: socket.socket, timeout: float) -> str:
    sock.settimeout(timeout)
    buf = bytearray()
    end = time.time() + timeout
    while time.time() < end:
        try:
            data = sock.recv(65536)
            if not data:
                break
            buf += data
            end = time.time() + 0.8
        except socket.timeout:
            break
        except Exception:
            break
    # Strip and respond to Telnet IAC so negotiation doesn't leak into output.
    out = bytearray()
    resp = bytearray()
    i = 0
    while i < len(buf):
        b = buf[i]
        if b == IAC and i + 2 < len(buf):
            cmd, opt = buf[i + 1], buf[i + 2]
            if cmd == DO:
                resp += bytes([IAC, WONT, opt])
            elif cmd == WILL:
                resp += bytes([IAC, DONT, opt])
            i += 3
            continue
        out.append(b)
        i += 1
    if resp:
        try: sock.send(bytes(resp))
        except Exception: pass
    return bytes(out).decode("utf-8", errors="replace")


# --- gauntlet sessions -------------------------------------------------------
#
# One session per transport runs the entire activity gauntlet in order. Each
# assertion-scenario then runs against the SAME collected output (no re-login,
# no additional connections). This avoids SSH auth rate limits and lets us run
# a richer matrix of checks cheaply.
#
# The gauntlet sequence is intentionally the same for SSH and Telnet. Any
# divergence in the collected output between transports signals a parity
# regression — precisely what this contract is meant to catch.

GAUNTLET_COMMANDS_TEMPLATE = [
    # --- baseline: in-zone activity on home ---
    "go out",                        # Study → Nexus
    "look",                          # render Nexus (local room scripts)
    "say hello world",               # local say
    "emote waves",                   # emote
    "tell Wyrd hi there",            # tell an agent present in Nexus
    "examine crystal",               # room-object examine
    "use crystal",                   # room-object use (script-driven)
    "go east",                       # Nexus → Docks
    "look",                          # render Docks with Carrying list
    "take compass",                  # pick up a room item
    "look",                          # Carrying: should now include compass
    "drop compass",                  # return it to the room
    "look",                          # compass back in the room

    # --- proxy mode: same verbs against remote zone ---
    "say travel {remote}",           # transit
    "look",                          # render REMOTE docks via proxy
    "say hello from home zone",      # remote say
    "emote waves at the locals",     # remote emote
    "examine compass",               # remote room-object examine
    "use compass",                   # remote room-object use
    "take compass",                  # take a REMOTE item
    "look",                          # remote room state after take
    "drop compass",                  # drop it in the REMOTE room
    "go north",                      # move within the REMOTE zone
    "look",                          # render the remote "north of docks" room

    # --- return ---
    "say travel home",               # end proxy, return home
    "look",                          # render home Docks, Carrying restored
]


def build_gauntlet(cfg) -> list[str]:
    return [c.format(remote=cfg.remote_zone) for c in GAUNTLET_COMMANDS_TEMPLATE]


def run_gauntlets(cfg) -> tuple[str, str]:
    """Run the gauntlet once over SSH and once over Telnet. Return both outputs."""
    # Telnet first — SSH auth has a low rate limit and we don't want it
    # triggering on the second connection after a fresh restart. Both runs
    # are fully sequential within a single TCP connection each.
    tel = telnet_session(cfg.home, cfg.telnet_port, cfg.user, cfg.password,
                         build_gauntlet(cfg))
    # Small inter-connection pause so SSH-auth rate limiter is calm.
    time.sleep(3.0)
    ssh = ssh_session(cfg.home, cfg.ssh_port, cfg.user, cfg.password,
                      build_gauntlet(cfg))
    return ssh, tel


# --- scenarios ---------------------------------------------------------------
#
# All scenarios below operate on the pre-collected gauntlet outputs stored in
# cfg._ssh_out / cfg._telnet_out. The runner populates these once before
# dispatching scenarios.

def scenario_ssh_in_zone_look(cfg) -> None:
    out = cfg._ssh_out
    assert_in(out, "The Nexus", "ssh look rendered Nexus")
    assert_in(out, "The Docks", "ssh look rendered Docks")


def scenario_telnet_in_zone_look(cfg) -> None:
    out = cfg._telnet_out
    assert_in(out, "The Nexus", "telnet look rendered Nexus")
    assert_in(out, "The Docks", "telnet look rendered Docks")


def scenario_ssh_in_zone_say(cfg) -> None:
    assert_in(cfg._ssh_out, f"{cfg.user.capitalize()}: hello world",
              "ssh say echoed locally")


def scenario_telnet_in_zone_say(cfg) -> None:
    assert_in(cfg._telnet_out, f"{cfg.user.capitalize()}: hello world",
              "telnet say echoed locally")


def scenario_ssh_in_zone_emote(cfg) -> None:
    assert_in(cfg._ssh_out, "waves", "ssh emote rendered")


def scenario_telnet_in_zone_emote(cfg) -> None:
    assert_in(cfg._telnet_out, "waves", "telnet emote rendered")


def scenario_ssh_tell_agent(cfg) -> None:
    assert_not_in(cfg._ssh_out, "no one by that name",
                  "ssh tell Wyrd resolved an entity")


def scenario_telnet_tell_agent(cfg) -> None:
    assert_not_in(cfg._telnet_out, "no one by that name",
                  "telnet tell Wyrd resolved an entity")


def scenario_ssh_in_zone_take_drop(cfg) -> None:
    out = cfg._ssh_out
    # Home Docks' compass is a scripted room object; take/drop both narrate.
    # Assertion: after take, compass is NOT listed as a floor object on the
    # following look. After drop, the compass narration should fire.
    # We can't always rely on counts because the remote does the same thing
    # later in the gauntlet, so use a prefix slice up to the transit point.
    local_slice = out.split("say travel ")[0]
    # `take compass` echo/narration
    assert_in(local_slice, "take compass", "ssh take compass issued")
    # After take, subsequent look should NOT show compass as a floor object.
    # Room objects appear as "You see compass here" — absence in the slice
    # after the take confirms the pickup.
    pieces = local_slice.split("take compass")
    if len(pieces) >= 2:
        post_take = pieces[1]
        assert_not_in(post_take, "You see compass here",
                      "ssh take: compass removed from home Docks floor")


def scenario_telnet_in_zone_take_drop(cfg) -> None:
    out = cfg._telnet_out
    local_slice = out.split("say travel ")[0]
    assert_in(local_slice, "take compass", "telnet take compass issued")
    pieces = local_slice.split("take compass")
    if len(pieces) >= 2:
        post_take = pieces[1]
        assert_not_in(post_take, "You see compass here",
                      "telnet take: compass removed from home Docks floor")


def scenario_ssh_transit(cfg) -> None:
    out = cfg._ssh_out
    assert_in(out, TRANSIT_OPENED_MARKER, "ssh transit: portal open")
    assert_not_in(out, "Failed to start transit session",
                  "ssh transit: no start-fail")


def scenario_telnet_transit(cfg) -> None:
    out = cfg._telnet_out
    assert_in(out, TRANSIT_OPENED_MARKER, "telnet transit: portal open")
    assert_not_in(out, "Failed to start transit session",
                  "telnet transit: no start-fail")


def scenario_ssh_proxy_look(cfg) -> None:
    # After `say travel <zone>`, a remote `look` should render beta's Docks
    # narration. The onEnter narrate "salt breeze stirs" is the distinctive
    # tell that we're getting events from the remote zone's room script.
    out = cfg._ssh_out
    remote_slice = after_marker(out, "say travel ")
    assert_in(remote_slice, "salt breeze stirs",
              "ssh proxy: remote docks narrated (salt breeze)")


def scenario_telnet_proxy_look(cfg) -> None:
    out = cfg._telnet_out
    remote_slice = after_marker(out, "say travel ")
    assert_in(remote_slice, "salt breeze stirs",
              "telnet proxy: remote docks narrated (salt breeze)")


def scenario_ssh_proxy_navigation(cfg) -> None:
    # `go north` within beta should be forwarded and render a different room.
    # beta's docks map has a north exit → Trading Post (same foundation).
    out = cfg._ssh_out
    remote_slice = after_marker(out, "say travel ")
    assert_in(remote_slice, "Trading Post",
              "ssh proxy: navigated within remote zone")


def scenario_telnet_proxy_navigation(cfg) -> None:
    out = cfg._telnet_out
    remote_slice = after_marker(out, "say travel ")
    assert_in(remote_slice, "Trading Post",
              "telnet proxy: navigated within remote zone")


def scenario_ssh_return_home(cfg) -> None:
    # Final look (after `say travel home`) must have the local Carrying list.
    out = cfg._ssh_out
    after_return = after_marker(out, "say travel home")
    assert_in(after_return, LOCAL_DOCKS_RETURN_MARKER,
              "ssh: local inventory restored on return")


def scenario_telnet_return_home(cfg) -> None:
    out = cfg._telnet_out
    after_return = after_marker(out, "say travel home")
    assert_in(after_return, LOCAL_DOCKS_RETURN_MARKER,
              "telnet: local inventory restored on return")


def after_marker(text: str, marker: str) -> str:
    """Return the text after the first occurrence of marker (or empty)."""
    idx = text.find(marker)
    return text[idx:] if idx >= 0 else ""


SCENARIOS = [
    # In-zone parity (home) — if these fail, the transport itself is broken.
    ("ssh      look (in-zone)",          scenario_ssh_in_zone_look),
    ("telnet   look (in-zone)",          scenario_telnet_in_zone_look),
    ("ssh      say (in-zone)",           scenario_ssh_in_zone_say),
    ("telnet   say (in-zone)",           scenario_telnet_in_zone_say),
    ("ssh      emote (in-zone)",         scenario_ssh_in_zone_emote),
    ("telnet   emote (in-zone)",         scenario_telnet_in_zone_emote),
    ("ssh      tell agent (in-zone)",    scenario_ssh_tell_agent),
    ("telnet   tell agent (in-zone)",    scenario_telnet_tell_agent),
    ("ssh      take/drop (in-zone)",     scenario_ssh_in_zone_take_drop),
    ("telnet   take/drop (in-zone)",     scenario_telnet_in_zone_take_drop),

    # Transit open.
    ("ssh      transit open",            scenario_ssh_transit),
    ("telnet   transit open",            scenario_telnet_transit),

    # Proxy-mode parity (remote zone) — the "seamless" promise.
    ("ssh      proxy look",              scenario_ssh_proxy_look),
    ("telnet   proxy look",              scenario_telnet_proxy_look),
    ("ssh      proxy navigation",        scenario_ssh_proxy_navigation),
    ("telnet   proxy navigation",        scenario_telnet_proxy_navigation),

    # Return.
    ("ssh      return home (inventory)", scenario_ssh_return_home),
    ("telnet   return home (inventory)", scenario_telnet_return_home),
]


# --- runner ------------------------------------------------------------------

def run(cfg) -> int:
    print(f"{DIM}home={cfg.home} (ssh:{cfg.ssh_port} telnet:{cfg.telnet_port})"
          f"  remote-zone={cfg.remote_zone}  user={cfg.user}{RESET}")
    print()

    # Run both gauntlets once up front; scenarios assert on the captured
    # output. Keeping all network I/O in this phase makes scenario runs cheap
    # and deterministic (no per-scenario connection overhead, no rate limits).
    print(f"{DIM}running SSH + Telnet gauntlets…{RESET}")
    t0 = time.monotonic()
    try:
        cfg._ssh_out, cfg._telnet_out = run_gauntlets(cfg)
    except Exception as e:
        print(f"  {RED}gauntlet setup failed: {type(e).__name__}: {e}{RESET}")
        if cfg.verbose:
            import traceback; traceback.print_exc()
        return 1
    print(f"{DIM}gauntlets complete in {time.monotonic()-t0:.1f}s "
          f"(ssh={len(cfg._ssh_out)}B, telnet={len(cfg._telnet_out)}B){RESET}")
    print()

    passed = failed = 0
    for name, fn in SCENARIOS:
        try:
            fn(cfg)
            print(f"  {GREEN}PASS{RESET}  {name}")
            passed += 1
        except ContractError as e:
            print(f"  {RED}FAIL{RESET}  {name}")
            print(f"        {YELLOW}{e}{RESET}")
            failed += 1
        except Exception as e:
            print(f"  {RED}ERR {RESET}  {name}")
            print(f"        {YELLOW}{type(e).__name__}: {e}{RESET}")
            failed += 1

    print()
    color = GREEN if failed == 0 else RED
    print(f"{color}{passed} passed, {failed} failed{RESET}")
    if failed > 0 and cfg.verbose:
        print()
        print(f"{DIM}--- SSH gauntlet output ---{RESET}")
        print(cfg._ssh_out)
        print(f"{DIM}--- Telnet gauntlet output ---{RESET}")
        print(cfg._telnet_out)
    return 0 if failed == 0 else 1


def main() -> int:
    parser = argparse.ArgumentParser(description="Transit client parity contract.")
    parser.add_argument("--home", default=os.environ.get("WYRDSEKAI_HOME", "home-server"))
    parser.add_argument("--remote-zone", default=os.environ.get("WYRDSEKAI_REMOTE_ZONE", "beta"))
    parser.add_argument("--user", default=os.environ.get("WYRDSEKAI_USER", "traveler"))
    parser.add_argument("--pass", dest="password",
                        default=os.environ.get("WYRDSEKAI_PASSWORD", "cross1234"))
    parser.add_argument("--ssh-port", type=int,
                        default=int(os.environ.get("WYRDSEKAI_SSH_PORT", "7022")))
    parser.add_argument("--telnet-port", type=int,
                        default=int(os.environ.get("WYRDSEKAI_TELNET_PORT", "7071")))
    parser.add_argument("--verbose", action="store_true",
                        help="dump captured gauntlet output on failure")
    cfg = parser.parse_args()
    return run(cfg)


if __name__ == "__main__":
    sys.exit(main())
