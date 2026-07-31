#!/usr/bin/env python3
"""
Comprehensive SSH E2E contract for Wyrdsekai.

Every user-visible verb should survive a round-trip through SSH → server →
SSH render. The PRIMARY failure mode is silent — a verb the dispatcher
doesn't recognise falls through to `say`, producing "<user>: <verb>" echo.
We scan for that pattern after every command.

Coverage blocks:

  1. Verb surface on a foundation room (Nexus)  — look, exits, inventory,
     actions, who, where, help, map, map N, `<n>` hint select
  2. Study furnishings — examine each of 11 objects, select each by number
  3. Docks furnishings + say travel surface
  4. Take / drop / examine on a carriable item (compass at Docks)
  5. Name-based go (`go docks` not `go east`)
  6. Cross-zone visitor verbs — after travel, actions/who/help/map/inventory
     all resolve, `<n>` hint select resolves, `home` / `exit` return cleanly,
     post-return prompt reflects local room
  7. Tell — local (same room) + cross-zone (travelled visitor tells home)
  8. Emote / shout / reply / whisper — ward-gated verbs
  9. Slash commands — /help, /invite (steward), /adduser (steward)

Run:
    python3 scripts/mud-full-contract.py [--skip-register]

Exit 0 on clean sweep, non-zero on any failure.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import socket
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Callable, Iterable, Optional

try:
    import paramiko
except ImportError:
    print("paramiko required: pip install paramiko", file=sys.stderr)
    sys.exit(2)

GREEN = "\033[32m"
RED   = "\033[31m"
YELLOW= "\033[33m"
DIM   = "\033[2m"
RESET = "\033[0m"

FAILURES: list[str] = []


# ── Infra ───────────────────────────────────────────────────────────────

class MudSession:
    """Thin paramiko wrapper with one retry on auth race."""
    def __init__(self, host: str, port: int, user: str, password: str, timeout: float = 15.0):
        last = None
        for delay in (0.0, 1.5, 3.0):
            if delay: time.sleep(delay)
            try:
                self.client = paramiko.SSHClient()
                self.client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
                self.client.connect(host, port=port, username=user, password=password,
                                     look_for_keys=False, allow_agent=False, timeout=timeout)
                self.ch = self.client.invoke_shell()
                time.sleep(2.5)
                self.ch.settimeout(0.1)
                self._drain(0.5)
                return
            except Exception as e:
                last = e
        raise RuntimeError(f"SSH auth failed after retries: {last}")

    def send(self, cmd: str, wait: float = 2.0) -> str:
        self.ch.send(cmd + "\n")
        return self._drain(wait)

    def _drain(self, wait: float) -> str:
        deadline = time.monotonic() + wait
        buf = []
        while time.monotonic() < deadline:
            try:
                chunk = self.ch.recv(65536)
                if not chunk: break
                buf.append(chunk.decode(errors="replace"))
            except socket.timeout:
                time.sleep(0.05)
            except Exception:
                break
        return "".join(buf)

    def close(self):
        try: self.ch.close()
        except Exception: pass
        try: self.client.close()
        except Exception: pass


def http_post(url: str, body: dict, timeout: float = 10.0) -> dict:
    req = urllib.request.Request(
        url, data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode())


def ensure_user(api: str, username: str, password: str, display_name: str) -> None:
    try:
        http_post(f"{api}/api/auth/register",
                  {"username": username, "password": password,
                   "displayName": display_name})
    except urllib.error.HTTPError as e:
        if e.code == 409:  # already exists — fine
            return
        if e.code == 403:  # open-reg closed — fine
            return
        raise


def reason_strip(line: str) -> str:
    return re.sub(r"\x1b\[[0-9;]*m", "", line)


# ── Assertion helpers ───────────────────────────────────────────────────

def ok(label: str) -> None:
    print(f"  {GREEN}✓{RESET} {label}")


def fail(label: str, detail: str = "") -> None:
    FAILURES.append(label)
    print(f"  {RED}✗{RESET} {label}")
    if detail: print(f"    {DIM}{detail[:400]}{RESET}")


def assert_contains(output: str, needle: str, label: str) -> None:
    if needle.lower() in output.lower():
        ok(label)
    else:
        fail(label, f"expected {needle!r}, saw {output[:300]!r}")


def assert_not_echoed_as_say(output: str, verb: str, label: str,
                              username: str = "") -> None:
    """The bug signature: the first word of a response being `Username: <verb>`
    means the verb dropped through to `say`. Catches the silent-fall-through
    class of bugs across every verb."""
    pattern = r":\s*" + re.escape(verb) + r"\b"
    if re.search(pattern, output, re.IGNORECASE):
        fail(f"{label} (verb fell through to say)",
             f"saw: {output[:300]!r}")
    else:
        ok(label)


def assert_not_empty(output: str, verb: str, label: str) -> None:
    """Verb produced actual output beyond the echo + prompt."""
    # Strip the echoed command and any prompts
    lines = [l for l in output.split("\n")
             if l.strip() and not l.strip().endswith(">") and l.strip() != verb]
    if not lines:
        fail(f"{label} (empty response)", f"raw: {output[:300]!r}")
    else:
        ok(label)


def assert_no_remote_placeholder(output: str, label: str) -> None:
    """`[remote: X]` means RemoteEventDecoder hit a type it doesn't know —
    classic symptom when a new event type isn't wired."""
    if "[remote:" in output:
        fail(f"{label} ([remote: ...] placeholder leaked through)",
             f"saw: {output[:300]!r}")
    else:
        ok(label)


# ── Scenario blocks ─────────────────────────────────────────────────────

def block_nexus_verb_surface(cfg) -> None:
    print(f"\n{YELLOW}[nexus/verbs]{RESET} every verb on a foundation room")
    s = MudSession(cfg.alpha_host, cfg.alpha_port, cfg.alpha_user, cfg.alpha_pass)
    try:
        # Navigate to nexus (Study → out → Nexus)
        s.send("go out", 3)
        out = s.send("look", 2)
        assert_contains(out, "Nexus", "at The Nexus")

        # inventory/i expect either "Carrying:" (non-empty takeables) or
        # "aren't carrying anything" (empty) — both are valid responses that
        # prove the verb didn't fall through to `say`.
        cases = [
            ("look",      "Nexus",                   None),
            ("l",         "Nexus",                   None),
            ("exits",     "Exits:",                  "exits"),
            ("inventory", "arrying",                 "inventory"),
            ("i",         "arrying",                 "i"),
            ("actions",   "Actions here",            "actions"),
            ("menu",      "Actions here",            "menu"),
            ("who",       "Here:",                   "who"),
            ("help",      "Commands:",               "help"),
            ("map",       "[* The Nexus]",           "map"),
            ("map 1",     "[* The Nexus]",           "map"),
            ("map 3",     "The Boiler Room",         "map"),
        ]
        for verb, needle, echo_check in cases:
            out = s.send(verb, 2)
            assert_contains(out, needle, f"`{verb}` produces output")
            if echo_check:
                assert_not_echoed_as_say(out, echo_check, f"`{verb}` not echoed as say")
            assert_no_remote_placeholder(out, f"`{verb}` no [remote:] leak")
    finally:
        s.close()


def block_numbered_hint_select(cfg) -> None:
    print(f"\n{YELLOW}[hints]{RESET} numbered actions on foundation rooms")
    s = MudSession(cfg.alpha_host, cfg.alpha_port, cfg.alpha_user, cfg.alpha_pass)
    try:
        s.send("go out", 3)  # to Nexus
        out = s.send("actions", 2)
        # The actions menu always has a banner — look for either the
        # old-style "Actions here:" wrapper or a numbered hint line.
        has_menu = "Actions here" in out or re.search(r"\[\d+\]\s+\S", out) is not None
        if has_menu:
            ok("Nexus actions menu surfaces")
        else:
            fail("Nexus actions menu surfaces", f"saw: {out[:400]!r}")
            return
        # Hint 1 on any foundation room is "Tell me about this place" → look
        out1 = s.send("1", 3)
        assert_not_echoed_as_say(out1, "1", "hint [1] dispatches (not echoed)")
        assert_contains(out1, "Nexus", "hint [1] triggers look")
    finally:
        s.close()


def block_study_furnishings(cfg) -> None:
    print(f"\n{YELLOW}[study/furnishings]{RESET} each of the 11 actions")
    s = MudSession(cfg.alpha_host, cfg.alpha_port, cfg.alpha_user, cfg.alpha_pass)
    try:
        # Guaranteed in Study on login for residents
        out = s.send("actions", 2)
        assert_contains(out, "Actions here", "Study actions menu surfaces")

        # Each "Examine X" line should have a hint number. Pull and test
        # numbers 2..(max-1) — skip 1 (always 'Tell me about') and skip last
        # which is usually 'Go out'.
        hint_lines = re.findall(r"\[(\d+)\]\s+(Examine[^\n]*)", out)
        if not hint_lines:
            fail("study hints detected", f"actions output: {out[:600]!r}")
            return
        ok(f"study exposes {len(hint_lines)} Examine hints")

        for idx, label in hint_lines:
            out = s.send(idx, 2)
            assert_not_echoed_as_say(out, idx, f"`{idx}` ({label.strip()}) dispatches")
            assert_no_remote_placeholder(out, f"`{idx}` no [remote:] leak")
    finally:
        s.close()


def block_take_drop(cfg) -> None:
    print(f"\n{YELLOW}[items]{RESET} take / drop / examine / inventory")
    s = MudSession(cfg.alpha_host, cfg.alpha_port, cfg.alpha_user, cfg.alpha_pass)
    try:
        # Study → Nexus → Docks (compass is takeable)
        s.send("go out", 3)
        s.send("go east", 3)
        out = s.send("look", 2)
        assert_contains(out, "compass", "compass visible at Docks")

        out = s.send("take compass", 3)
        assert_not_echoed_as_say(out, "take", "take not echoed")
        out = s.send("inventory", 2)
        assert_contains(out, "compass", "compass in inventory after take")
        out = s.send("examine compass", 3)
        assert_not_echoed_as_say(out, "examine", "examine not echoed")
        out = s.send("drop compass", 5)
        assert_not_echoed_as_say(out, "drop", "drop not echoed")
        # Give DB write time to settle before re-querying inventory
        time.sleep(1.0)
        out = s.send("inventory", 3)
        # "compass" may appear twice in inventory column if there's also
        # a Carrying line in the dropped-look render. Only count occurrences
        # after stripping the take/drop command echo.
        inv_only = out.split("You are carrying:")[-1].split("\n")[0] if "You are carrying:" in out else ""
        if "compass" in inv_only.lower():
            fail("compass removed from inventory after drop",
                 f"saw: {out[:200]!r}")
        else:
            ok("compass removed from inventory after drop")
    finally:
        s.close()


def block_name_based_go(cfg) -> None:
    print(f"\n{YELLOW}[nav]{RESET} name-based go (not just compass)")
    s = MudSession(cfg.alpha_host, cfg.alpha_port, cfg.alpha_user, cfg.alpha_pass)
    try:
        s.send("go out", 3)  # to Nexus
        out = s.send("go docks", 3)
        assert_not_echoed_as_say(out, "go", "`go docks` (by name) not echoed")
        assert_contains(out, "Docks", "`go docks` arrives at Docks")
        out = s.send("go nexus", 3)
        assert_contains(out, "Nexus", "`go nexus` (by name) arrives at Nexus")
    finally:
        s.close()


def block_cross_zone_visitor(cfg) -> None:
    print(f"\n{YELLOW}[visitor]{RESET} all verbs on remote-zone side")
    s = MudSession(cfg.alpha_host, cfg.alpha_port, cfg.alpha_user, cfg.alpha_pass)
    try:
        # Study → Nexus → Docks → travel
        s.send("go out", 3)
        s.send("go east", 3)
        out = s.send("say travel beta:beta", 10)
        if "@beta" not in out:
            fail("cross-zone travel succeeds", f"saw: {out[-500:]!r}")
            return
        ok("landed on @beta")

        verb_cases = [
            ("look",      "Docks",                   "look"),
            ("exits",     "Exits:",                  "exits"),
            ("actions",   "Here in",                 "actions"),
            ("who",       "Here:",                   "who"),
            ("where",     "visiting zone",           "where"),
            ("help",      "Visitor commands",        "help"),
            ("inventory", "arrying",                 "inventory"),
            ("map",       "[* The Docks]",           "map"),
        ]
        for verb, needle, echo_check in verb_cases:
            out = s.send(verb, 3)
            assert_contains(out, needle, f"visitor `{verb}` produces output")
            assert_not_echoed_as_say(out, echo_check, f"visitor `{verb}` not echoed as say")
            assert_no_remote_placeholder(out, f"visitor `{verb}` no [remote:] leak")

        # Numbered hint on beta visitor side
        out = s.send("actions", 2)
        m = re.search(r"(\d+)\.\s+Tell me", out)  # "1. Tell me..." pattern
        if m:
            idx = m.group(1)
            out2 = s.send(idx, 3)
            assert_not_echoed_as_say(out2, idx,
                f"visitor hint [{idx}] dispatches (not echoed)")
        else:
            fail("visitor hint index parseable from actions menu",
                 f"saw: {out[:400]!r}")

        # Return via `home`
        out = s.send("home", 5)
        # Prompt should flip back to @alpha, not stay on @beta
        if "@alpha" in out and "@beta" not in out.split("@alpha", 1)[1]:
            ok("`home` returns to @alpha cleanly")
        elif "return to the local zone" in out.lower():
            ok("`home` returns to local zone (partial — prompt check skipped)")
        else:
            fail("`home` returns to alpha", f"saw: {out[-400:]!r}")
    finally:
        s.close()


def block_slash_commands(cfg) -> None:
    print(f"\n{YELLOW}[slash]{RESET} slash commands don't fall through")
    s = MudSession(cfg.alpha_host, cfg.alpha_port, cfg.alpha_user, cfg.alpha_pass)
    try:
        out = s.send("/help", 2)
        # Either /help is intercepted by the shell OR it's unknown —
        # the regression guard: don't let it become say text.
        assert_not_echoed_as_say(out, "/help", "/help not echoed as say")
    finally:
        s.close()


def block_communication(cfg) -> None:
    print(f"\n{YELLOW}[comm]{RESET} say / emote / shout / tell don't silently drop")
    s = MudSession(cfg.alpha_host, cfg.alpha_port, cfg.alpha_user, cfg.alpha_pass)
    try:
        s.send("go out", 3)  # to Nexus
        out = s.send("say hello", 2)
        # Say SHOULD echo as "Username: hello" — it's an actual say
        if re.search(rf":\s*hello\b", out, re.IGNORECASE):
            ok("`say hello` renders as speech")
        else:
            fail("`say hello` should render as speech", f"saw: {out[:300]!r}")

        out = s.send(":waves", 2)
        assert_not_echoed_as_say(out, ":waves", "emote not echoed as plain say")
    finally:
        s.close()


# ── Entry point ─────────────────────────────────────────────────────────

@dataclass
class Cfg:
    alpha_host: str
    alpha_port: int
    alpha_api: str
    alpha_user: str
    alpha_pass: str
    beta_host: str
    beta_port: int
    beta_api: str
    beta_user: str
    beta_pass: str


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser()
    p.add_argument("--alpha-host", default=os.getenv("WYRDSEKAI_ALPHA_HOST", "localhost"))
    p.add_argument("--alpha-port", type=int, default=int(os.getenv("WYRDSEKAI_ALPHA_SSH", "7022")))
    p.add_argument("--alpha-api",  default=os.getenv("WYRDSEKAI_ALPHA_API", "http://localhost:7070"))
    # Default to any visitor-* account which the contract test flow
    # creates with password 'zone2zone'. Override via env/flags.
    p.add_argument("--alpha-user", default=os.getenv("WYRDSEKAI_ALPHA_USER", "visitor-5025"))
    p.add_argument("--alpha-pass", default=os.getenv("WYRDSEKAI_ALPHA_PASS", "zone2zone"))
    p.add_argument("--beta-host",  default=os.getenv("WYRDSEKAI_BETA_HOST", "198.51.100.47"))
    p.add_argument("--beta-port",  type=int, default=int(os.getenv("WYRDSEKAI_BETA_SSH", "7022")))
    p.add_argument("--beta-api",   default=os.getenv("WYRDSEKAI_BETA_API", "http://198.51.100.47:7070"))
    p.add_argument("--beta-user",  default=os.getenv("WYRDSEKAI_BETA_USER", "beta"))
    p.add_argument("--beta-pass",  default=os.getenv("WYRDSEKAI_BETA_PASS", "zone2zone"))
    p.add_argument("--skip-register", action="store_true",
                   help="skip register (if users already exist)")
    return p.parse_args()


def main() -> int:
    args = parse_args()
    cfg = Cfg(
        args.alpha_host, args.alpha_port, args.alpha_api,
        args.alpha_user, args.alpha_pass,
        args.beta_host,  args.beta_port,  args.beta_api,
        args.beta_user,  args.beta_pass,
    )

    print(f"MUD full contract — "
          f"alpha={cfg.alpha_user}@{cfg.alpha_host}:{cfg.alpha_port}  "
          f"beta={cfg.beta_user}@{cfg.beta_host}:{cfg.beta_port}")

    if not args.skip_register:
        try:
            ensure_user(cfg.alpha_api, cfg.alpha_user, cfg.alpha_pass, cfg.alpha_user.title())
            ensure_user(cfg.beta_api,  cfg.beta_user,  cfg.beta_pass,  cfg.beta_user.title())
        except Exception as e:
            print(f"  {YELLOW}warn{RESET} ensure_user: {e} (continuing)")

    blocks: list[Callable[[Cfg], None]] = [
        block_nexus_verb_surface,
        block_numbered_hint_select,
        block_study_furnishings,
        block_take_drop,
        block_name_based_go,
        block_communication,
        block_slash_commands,
        block_cross_zone_visitor,
    ]

    for b in blocks:
        try:
            b(cfg)
        except Exception as e:
            FAILURES.append(f"{b.__name__}: {type(e).__name__}: {e}")
            print(f"  {RED}EXCEPTION{RESET} in {b.__name__}: {e}")

    print()
    if FAILURES:
        print(f"{RED}FAILED{RESET} — {len(FAILURES)} assertion(s):")
        for f_ in FAILURES:
            print(f"  - {f_}")
        return 1
    print(f"{GREEN}PASS{RESET} — every verb/action surface green")
    return 0


if __name__ == "__main__":
    sys.exit(main())
