#!/usr/bin/env python3
"""
Wyrdsekai MCP Server for Claude Code.

Exposes the companion, library, oracle, and study as tools
that Claude Code can call during development sessions.

Zero external dependencies — uses only Python stdlib.

Setup in Claude Code:
  Add to .claude/settings.json:
  {
    "mcpServers": {
      "wyrdsekai": {
        "command": "python3",
        "args": ["/path/to/wyrdsekai/scripts/mcp/wyrdsekai_mcp.py"],
        "env": { "WYRDSEKAI_URL": "http://localhost:7070" }
      }
    }
  }

Or via `wyrdsekai mcp-serve` if installed.
"""

import base64
import hashlib
import json
import os
import queue
import socket
import struct
import sys
import threading
import time
import urllib.request
import urllib.error
import urllib.parse
import uuid

# ─── Configuration ──────────────────────────────────────────────────────

BASE_URL = os.environ.get("WYRDSEKAI_URL", "http://localhost:7070")
COMPANION = os.environ.get("WYRDSEKAI_COMPANION", "Wyrd")
PLAYER = os.environ.get("WYRDSEKAI_PLAYER", "claude")
# Password for the configured player. The server is invite-only: open
# registration is a one-shot first-steward window that closes after the first
# account, so on an established zone you MUST point at an existing account.
# Set WYRDSEKAI_PLAYER + WYRDSEKAI_PASSWORD (account made via `wyrd invite` /
# the login door). Falls back to a derived password only for first-run bootstrap.
PLAYER_PASSWORD = os.environ.get("WYRDSEKAI_PASSWORD")

# ─── HTTP helpers ───────────────────────────────────────────────────────

def api_get(path, params=None):
    """GET request to Wyrdsekai API. Returns parsed JSON or error string."""
    url = BASE_URL + path
    if params:
        url += "?" + urllib.parse.urlencode(params)
    try:
        req = urllib.request.Request(url, headers={"Accept": "application/json"})
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.loads(resp.read().decode())
    except Exception as e:
        return {"error": str(e)}


def api_post(path, body=None):
    """POST request to Wyrdsekai API. Returns parsed JSON or error string."""
    url = BASE_URL + path
    data = json.dumps(body or {}).encode() if body else None
    try:
        req = urllib.request.Request(
            url, data=data, method="POST",
            headers={"Content-Type": "application/json", "Accept": "application/json"})
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.loads(resp.read().decode())
    except Exception as e:
        return {"error": str(e)}


# ─── MCP REST session (replaces WebSocket for request/response tools) ──

_mcp_token = None  # Auth token for MCP REST API


def mcp_post(path, body=None, timeout=30):
    """POST to MCP REST API with Bearer token."""
    url = BASE_URL + path
    data = json.dumps(body or {}).encode() if body else None
    headers = {"Content-Type": "application/json", "Accept": "application/json"}
    if _mcp_token:
        headers["Authorization"] = f"Bearer {_mcp_token}"
    try:
        req = urllib.request.Request(url, data=data, method="POST", headers=headers)
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode())
    except Exception as e:
        return {"error": str(e)}


def mcp_get(path, params=None, timeout=30):
    """GET from MCP REST API with Bearer token."""
    url = BASE_URL + path
    if params:
        url += "?" + urllib.parse.urlencode(params)
    headers = {"Accept": "application/json"}
    if _mcp_token:
        headers["Authorization"] = f"Bearer {_mcp_token}"
    try:
        req = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode())
    except Exception as e:
        return {"error": str(e)}


def format_room(data):
    """Format room JSON from MCP REST API into readable text."""
    room = data.get("room") if data else None
    if not room:
        return data.get("message", "No room data.")
    parts = [f"[{room.get('name', '?')}]"]
    parts.append(room.get("description", ""))
    entities = room.get("entities", [])
    if entities:
        parts.append(f"Present: {', '.join(entities)}")
    exits = room.get("exits", [])
    if exits:
        exit_strs = [f"{e['direction']} → {e.get('label', e.get('target', '?'))}" for e in exits]
        parts.append(f"Exits: {', '.join(exit_strs)}")
    objects = room.get("objects", [])
    if objects:
        parts.append(f"Objects: {', '.join(o['name'] for o in objects)}")
    return "\n".join(parts)


# ─── Minimal WebSocket client (stdlib only) ───────────────────────────

class WebSocketClient:
    """Minimal RFC 6455 WebSocket client. Text frames only."""

    def __init__(self, url):
        self._url = url
        self._sock = None
        self._closed = False

    def connect(self):
        """Perform HTTP upgrade handshake and return self."""
        parsed = urllib.parse.urlparse(self._url)
        host = parsed.hostname
        port = parsed.port or (443 if parsed.scheme == "wss" else 80)
        path = parsed.path + ("?" + parsed.query if parsed.query else "")

        self._sock = socket.create_connection((host, port), timeout=10)
        self._sock.settimeout(3.0)  # for non-blocking reads in reader thread (3s allows large frames over WiFi)

        # WebSocket upgrade
        key = base64.b64encode(os.urandom(16)).decode()
        req = (
            f"GET {path} HTTP/1.1\r\n"
            f"Host: {host}:{port}\r\n"
            f"Upgrade: websocket\r\n"
            f"Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\n"
            f"Sec-WebSocket-Version: 13\r\n"
            f"\r\n"
        )
        self._sock.sendall(req.encode())

        # Read response headers
        resp = b""
        while b"\r\n\r\n" not in resp:
            chunk = self._sock.recv(4096)
            if not chunk:
                raise ConnectionError("Connection closed during handshake")
            resp += chunk

        status_line = resp.split(b"\r\n")[0].decode()
        if "101" not in status_line:
            raise ConnectionError(f"WebSocket upgrade failed: {status_line}")

        # Any data after headers is the start of the first frame
        _, _, remainder = resp.partition(b"\r\n\r\n")
        self._buffer = remainder
        return self

    def send_text(self, text):
        """Send a masked text frame."""
        payload = text.encode("utf-8")
        mask_key = os.urandom(4)

        # Frame header
        header = bytearray()
        header.append(0x81)  # FIN + text opcode
        length = len(payload)
        if length < 126:
            header.append(0x80 | length)  # masked
        elif length < 65536:
            header.append(0x80 | 126)
            header.extend(struct.pack(">H", length))
        else:
            header.append(0x80 | 127)
            header.extend(struct.pack(">Q", length))
        header.extend(mask_key)

        # Mask payload
        masked = bytearray(len(payload))
        for i in range(len(payload)):
            masked[i] = payload[i] ^ mask_key[i % 4]

        self._sock.sendall(bytes(header) + bytes(masked))

    def recv_frame(self):
        """Read one frame. Returns (opcode, payload_bytes) or None on close."""
        frame_state = [False]  # [0] = True once we've read the 2-byte header

        def read_exact(n):
            data = bytearray()
            # Drain buffer first
            if self._buffer:
                take = min(n, len(self._buffer))
                data.extend(self._buffer[:take])
                self._buffer = self._buffer[take:]
                n -= take
            while n > 0:
                try:
                    chunk = self._sock.recv(n)
                except socket.timeout:
                    # Between frames (waiting for next frame header): let caller handle
                    # Mid-frame (header read, waiting for payload): keep retrying
                    if not frame_state[0] and len(data) == 0:
                        raise
                    continue
                if not chunk:
                    return None
                data.extend(chunk)
                n -= len(chunk)
            return bytes(data)

        header = read_exact(2)
        if not header:
            return None
        frame_state[0] = True

        opcode = header[0] & 0x0F
        masked = bool(header[1] & 0x80)
        length = header[1] & 0x7F

        if length == 126:
            ext = read_exact(2)
            if not ext:
                return None
            length = struct.unpack(">H", ext)[0]
        elif length == 127:
            ext = read_exact(8)
            if not ext:
                return None
            length = struct.unpack(">Q", ext)[0]

        mask_key = read_exact(4) if masked else None

        payload = read_exact(length) if length > 0 else b""
        if payload is None:
            return None

        if masked and mask_key:
            payload = bytes(payload[i] ^ mask_key[i % 4] for i in range(len(payload)))

        return (opcode, payload)

    def recv_text(self):
        """Read next text frame, handling pings. Returns text or None on close."""
        while True:
            result = self.recv_frame()
            if result is None:
                return None
            opcode, payload = result
            if opcode == 0x1:  # text
                return payload.decode("utf-8", errors="replace")
            elif opcode == 0x9:  # ping → pong
                pong = bytearray([0x8A, 0x80 | len(payload)])
                pong.extend(os.urandom(4))  # mask key
                self._sock.sendall(bytes(pong))
            elif opcode == 0x8:  # close
                return None

    def close(self):
        """Send close frame and shut down."""
        if self._closed:
            return
        self._closed = True
        try:
            # Close frame: FIN + close opcode, masked, zero payload
            self._sock.sendall(b"\x88\x80" + os.urandom(4))
            self._sock.shutdown(socket.SHUT_RDWR)
        except Exception:
            pass
        try:
            self._sock.close()
        except Exception:
            pass


# ─── In-world session ─────────────────────────────────────────────────

class WyrdSession:
    """Persistent WebSocket session for first-person world presence."""

    def __init__(self):
        self._ws = None
        self._token = None
        self._room_id = None
        self._room_state = None
        self._inventory = []
        self._msg_queue = queue.Queue()
        self._reader_thread = None
        self._connected = False
        self._last_seq = 0

    @property
    def connected(self):
        return self._connected

    @property
    def room_id(self):
        return self._room_id

    def login(self, username=None, password=None, description=None):
        """Register/login and open WebSocket session."""
        username = username or PLAYER
        password = password or f"{username}-mcp-session"

        # Try login first, register if fails
        result = api_post("/api/auth/login",
                          {"username": username, "password": password})
        if "error" in result or "token" not in result:
            result = api_post("/api/auth/register",
                              {"username": username, "password": password,
                               "display_name": username.capitalize()})
        if "error" in result or "token" not in result:
            return f"Authentication failed: {result.get('error', 'no token')}"

        self._token = result["token"]

        # Open WebSocket
        ws_url = BASE_URL.replace("http://", "ws://").replace("https://", "wss://")
        ws_url += f"/ws?token={self._token}"

        try:
            self._ws = WebSocketClient(ws_url).connect()
        except Exception as e:
            return f"WebSocket connection failed: {e}"

        self._connected = True

        # Start reader thread
        self._reader_thread = threading.Thread(target=self._reader_loop, daemon=True)
        self._reader_thread.start()

        # Wait for initial room_state (server sends prose first, then room_state)
        initial = self._wait_for_type("room_state", timeout=15)
        if initial:
            self._apply_room_state(initial)
        else:
            return "Connected but no initial room state received."

        # Set description if provided
        if description:
            self._send_command("update_description", args=[],
                               payload={"description": description})

        room_name = self._room_state.get("room", {}).get("name", self._room_id)
        return f"Logged in as {username}. You are in: {room_name}"

    def logout(self):
        """Close session and leave the world."""
        if not self._connected:
            return "Not connected."
        self._connected = False
        if self._ws:
            self._ws.close()
        return "Disconnected from Wyrdsekai."

    def look(self, target=None):
        """Look at current room or a specific target."""
        if not self._connected:
            return "Not connected. Use wyrdsekai_login first."
        if target:
            self._send_say(f"look at {target}")
            prose = self._wait_for_type("prose", timeout=10)
            if prose:
                return prose.get("text", "You see nothing special.")
            return "No response."
        # Look at room — request fresh room_state
        self._drain_queue()
        self._send_msg({"type": "look", "id": self._next_id(), "roomId": self._room_id})
        rs = self._wait_for_type("room_state", timeout=10)
        if rs:
            self._apply_room_state(rs)
        return self._format_room()

    def go(self, direction):
        """Move in a direction."""
        if not self._connected:
            return "Not connected. Use wyrdsekai_login first."
        # Drain stale messages aggressively (companion may be chatty)
        self._drain_queue()
        time.sleep(0.3)
        self._drain_queue()
        old_room = self._room_id
        self._send_msg({
            "type": "go", "id": self._next_id(),
            "roomId": self._room_id, "direction": direction
        })
        # Wait for room_state (new room) or error
        deadline = time.time() + 10
        while time.time() < deadline:
            msg = self._wait_for_any(["room_state", "error", "prose"], timeout=8)
            if not msg:
                break
            if msg.get("type") == "error":
                return msg.get("message", "Cannot go that way.")
            if msg.get("type") == "prose":
                continue  # skip narrative, keep waiting for room_state
            if msg.get("type") == "room_state":
                self._apply_room_state(msg)
                return self._format_room()
        # Timeout — apply whatever we got
        return "Movement may have failed. Try 'look' to check."

    def do_command(self, text):
        """Send any command as speech/text input.

        Parses common verbs (use, take, drop, emote, look at) into their
        proper C2S message types instead of sending everything as 'say'.
        """
        if not self._connected:
            return "Not connected. Use wyrdsekai_login first."
        self._drain_queue()

        lower = text.strip().lower()

        # Parse structured commands

        # Namespaced zone commands (e.g., "hello.greet", "codeplane.status")
        if "." in lower.split(" ")[0] and not lower.startswith("look at "):
            parts = text.strip().split(" ", 1)
            ns_action = parts[0]  # e.g., "hello.greet"
            cmd_args = parts[1].split() if len(parts) > 1 else []
            self._send_msg({
                "type": "command", "id": self._next_id(),
                "command": ns_action,
                "args": cmd_args,
                "payload": {},
            })
        elif lower.startswith("use "):
            parts = text.strip()[4:].split(" on ", 1)
            obj = parts[0].strip()
            target = parts[1].strip() if len(parts) > 1 else None
            msg = {"type": "use", "id": self._next_id(),
                   "roomId": self._room_id, "objectName": obj}
            if target:
                msg["target"] = target
            self._send_msg(msg)
        elif lower.startswith("take "):
            obj = text.strip()[5:].strip()
            self._send_msg({"type": "take", "id": self._next_id(),
                            "roomId": self._room_id, "objectName": obj})
        elif lower.startswith("drop "):
            obj = text.strip()[5:].strip()
            self._send_msg({"type": "drop", "id": self._next_id(),
                            "roomId": self._room_id, "objectName": obj})
        elif lower.startswith("emote ") or lower.startswith("/me "):
            emote_text = text.strip().split(" ", 1)[1] if " " in text else ""
            self._send_msg({"type": "emote", "id": self._next_id(),
                            "roomId": self._room_id, "text": emote_text})
        elif lower.startswith("look at "):
            target = text.strip()[8:].strip()
            # Send as say — server's command parser handles 'look at X'
            self._send_say(f"look at {target}")
        else:
            self._send_say(text)

        # Collect responses
        responses = self._collect_responses(timeout=15)
        if not responses:
            return "(no response)"
        return "\n".join(responses)

    def actions(self):
        """List available actions in the current room."""
        if not self._connected:
            return "Not connected. Use wyrdsekai_login first."
        if not self._room_state:
            return "No room state available."
        room = self._room_state.get("room", {})
        parts = []
        hints = room.get("hints", [])
        if hints:
            parts.append("Suggested actions:")
            for h in hints:
                parts.append(f"  - {h.get('label', '?')} ({h.get('action', '?')})")
        objects = room.get("objects", [])
        if objects:
            parts.append("Objects:")
            for o in objects:
                desc = o.get("description", "")
                take = " [takeable]" if o.get("takeable") else ""
                parts.append(f"  - {o.get('name', '?')}: {desc}{take}")
        exits = room.get("exits", [])
        if exits:
            parts.append("Exits:")
            for e in exits:
                parts.append(f"  - {e.get('direction', '?')} → {e.get('label', e.get('targetRoom', '?'))}")
        entities = room.get("entities", [])
        if entities:
            parts.append("Present:")
            for e in entities:
                parts.append(f"  - {e.get('name', '?')} ({e.get('type', '?')})")
        return "\n".join(parts) if parts else "Nothing obvious to do here."

    def inventory(self):
        """List carried items."""
        if not self._connected:
            return "Not connected. Use wyrdsekai_login first."
        if not self._inventory:
            return "You are not carrying anything."
        parts = ["Inventory:"]
        for item in self._inventory:
            parts.append(f"  - {item.get('name', '?')}: {item.get('description', '')}")
        return "\n".join(parts)

    # ── Internal ──

    def _send_msg(self, msg):
        if self._ws:
            self._ws.send_text(json.dumps(msg))

    def _send_say(self, text):
        self._send_msg({
            "type": "say", "id": self._next_id(),
            "roomId": self._room_id, "text": text
        })

    def _send_command(self, cmd, args=None, payload=None):
        msg = {"type": "command", "id": self._next_id(), "command": cmd}
        if args:
            msg["args"] = args
        if payload:
            msg["payload"] = payload
        self._send_msg(msg)

    def _next_id(self):
        return f"mcp-{uuid.uuid4().hex[:8]}"

    def _reader_loop(self):
        """Background thread: read WebSocket messages into queue."""
        while self._connected:
            try:
                text = self._ws.recv_text()
                if text is None:
                    self._connected = False
                    break
                msg = json.loads(text)
                seq = msg.get("seq", 0)
                if seq > self._last_seq:
                    self._last_seq = seq
                self._msg_queue.put(msg)
            except socket.timeout:
                continue
            except Exception as e:
                if self._connected:
                    sys.stderr.write(f"[wyrdsekai-mcp] WS reader error: {e}\n")
                break

    def _wait_for_type(self, msg_type, timeout=10):
        """Wait for a specific message type from the queue."""
        deadline = time.time() + timeout
        stash = []
        try:
            while time.time() < deadline:
                try:
                    msg = self._msg_queue.get(timeout=0.5)
                    if msg.get("type") == msg_type:
                        # Put stashed messages back
                        for s in stash:
                            self._msg_queue.put(s)
                        return msg
                    stash.append(msg)
                except queue.Empty:
                    continue
        finally:
            for s in stash:
                self._msg_queue.put(s)
        return None

    def _wait_for_any(self, types, timeout=10):
        """Wait for any of the specified message types."""
        deadline = time.time() + timeout
        stash = []
        try:
            while time.time() < deadline:
                try:
                    msg = self._msg_queue.get(timeout=0.5)
                    if msg.get("type") in types:
                        for s in stash:
                            self._msg_queue.put(s)
                        return msg
                    stash.append(msg)
                except queue.Empty:
                    continue
        finally:
            for s in stash:
                self._msg_queue.put(s)
        return None

    def _collect_responses(self, timeout=15):
        """Collect prose/state_change/error messages for a period."""
        deadline = time.time() + timeout
        results = []
        idle_start = time.time()
        while time.time() < deadline:
            try:
                msg = self._msg_queue.get(timeout=1.0)
                idle_start = time.time()
                t = msg.get("type")
                if t == "prose":
                    speaker = msg.get("speaker", "")
                    text = msg.get("text", "")
                    # Filter out narrator noise (enter/leave/arrive events)
                    if speaker == "narrator" and any(
                        kw in text.lower() for kw in
                        ["enters from", "arrives at", "leaves ", "departs "]):
                        continue
                    if speaker and speaker != "system":
                        results.append(f"{speaker}: {text}")
                    elif text.strip():
                        results.append(text)
                elif t == "zone_response":
                    text = msg.get("text", "")
                    ns = msg.get("namespace", "")
                    if text:
                        results.append(f"[{ns}] {text}" if ns else text)
                    data = msg.get("data")
                    if data and not text:
                        results.append(f"[{ns}] {json.dumps(data)}")
                elif t == "state_change":
                    results.append(msg.get("description", "Something changed."))
                elif t == "room_state":
                    self._apply_room_state(msg)
                    results.append(self._format_room())
                elif t == "error":
                    results.append(f"Error: {msg.get('message', '?')}")
                elif t == "agent_action":
                    results.append(
                        f"{msg.get('agentName', '?')} {msg.get('description', '?')}")
                elif t == "notification":
                    results.append(f"[{msg.get('level', 'info')}] {msg.get('message', '')}")
            except queue.Empty:
                # If we got at least one result and have been idle >2s, we're done
                if results and (time.time() - idle_start) > 2.0:
                    break
                continue
        return results

    def _drain_queue(self):
        """Discard all pending messages in the queue."""
        while True:
            try:
                self._msg_queue.get_nowait()
            except queue.Empty:
                break

    def _apply_room_state(self, msg):
        room = msg.get("room", {})
        self._room_id = room.get("roomId", self._room_id)
        self._room_state = msg
        self._inventory = msg.get("inventory", self._inventory)

    def _format_room(self):
        if not self._room_state:
            return "No room state."
        room = self._room_state.get("room", {})
        parts = [f"[{room.get('name', room.get('roomId', '?'))}]"]
        desc = room.get("description", "")
        if desc:
            parts.append(desc)
        entities = room.get("entities", [])
        if entities:
            names = [e.get("name", "?") for e in entities]
            parts.append(f"Present: {', '.join(names)}")
        exits = room.get("exits", [])
        if exits:
            dirs = [f"{e.get('direction', '?')} → {e.get('label', e.get('targetRoom', '?'))}"
                    for e in exits]
            parts.append(f"Exits: {', '.join(dirs)}")
        objects = room.get("objects", [])
        if objects:
            names = [o.get("name", "?") for o in objects]
            parts.append(f"Objects: {', '.join(names)}")
        return "\n".join(parts)


# Global session instance
_session = WyrdSession()


# ─── Tool definitions ──────────────────────────────────────────────────

TOOLS = [
    # ── In-World Presence (WebSocket session) ──
    {
        "name": "wyrdsekai_login",
        "description": (
            "Log into Wyrdsekai as a player entity. Opens a WebSocket session "
            "and places you in the world. Other agents and players will see you. "
            "Call this before using go, look, do, actions, or inventory."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "username": {
                    "type": "string",
                    "description": "Player name (default: claude)"
                },
                "description": {
                    "type": "string",
                    "description": "How others see you when they look at you"
                }
            }
        }
    },
    {
        "name": "wyrdsekai_logout",
        "description": (
            "Leave Wyrdsekai. Closes the WebSocket session. "
            "Others will see you depart."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {}
        }
    },
    {
        "name": "wyrdsekai_go",
        "description": (
            "Move to another room via an exit direction. "
            "Returns the new room description, who is present, and exits."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "direction": {
                    "type": "string",
                    "description": "Exit direction (north, south, east, west, etc.)"
                }
            },
            "required": ["direction"]
        }
    },
    {
        "name": "wyrdsekai_do",
        "description": (
            "Execute any command in the world. Send natural language or MUD commands. "
            "Examples: 'say hello', 'take scroll', 'use key on door', "
            "'read inscription', 'pull lever', 'tell Wyrd about the bug I fixed'."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "command": {
                    "type": "string",
                    "description": "The command or speech to execute"
                }
            },
            "required": ["command"]
        }
    },
    {
        "name": "wyrdsekai_actions",
        "description": (
            "List what you can do in the current room. "
            "Shows interactive objects, suggested actions, exits, and who is present."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {}
        }
    },
    {
        "name": "wyrdsekai_inventory",
        "description": (
            "List items you are carrying."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {}
        }
    },
    # ── Companion (API-based, no session required) ──
    {
        "name": "wyrdsekai_tell",
        "description": (
            "Send a message to your Wyrdsekai companion. "
            "The companion is an AI agent that lives in the world, has memory, "
            "and can perform actions. Use this to ask questions, share context, "
            "or have the companion do things in the world."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "message": {
                    "type": "string",
                    "description": "The message to send to the companion"
                }
            },
            "required": ["message"]
        }
    },
    {
        "name": "wyrdsekai_ask",
        "description": (
            "Ask the companion a question and wait for a response. "
            "Unlike tell, this waits for the companion to reply. "
            "Use for questions like 'what happened since last session?' or "
            "'what patterns have you noticed?'"
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "question": {
                    "type": "string",
                    "description": "The question to ask the companion"
                }
            },
            "required": ["question"]
        }
    },
    {
        "name": "wyrdsekai_look",
        "description": (
            "Look at the current room or a specific entity/object. "
            "When logged in, returns first-person view with full room details. "
            "When not logged in, uses the companion's perspective via API."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "target": {
                    "type": "string",
                    "description": "Entity or object to look at (omit to look at the room)"
                },
                "room": {
                    "type": "string",
                    "description": "Room ID (only used when not logged in)"
                }
            }
        }
    },
    {
        "name": "wyrdsekai_library_search",
        "description": (
            "Search the Wyrdsekai knowledge library. "
            "Returns matching documents from indexed knowledge packs. "
            "Useful for finding information the household has collected."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "Search query"
                },
                "limit": {
                    "type": "integer",
                    "description": "Max results (default: 5)",
                    "default": 5
                }
            },
            "required": ["query"]
        }
    },
    {
        "name": "wyrdsekai_oracle",
        "description": (
            "Query the Oracle for predictions and patterns. "
            "The Oracle analyzes activity patterns and temporal data "
            "to surface insights about habits, trends, and upcoming events."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "topic": {
                    "type": "string",
                    "description": "Topic to query (e.g., 'activity patterns', 'recent changes')"
                }
            },
            "required": ["topic"]
        }
    },
    {
        "name": "wyrdsekai_study_read",
        "description": (
            "Read a document from the player's private Study. "
            "The Study holds personal notes, journal entries, and documents."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "document": {
                    "type": "string",
                    "description": "Document name or search query"
                }
            },
            "required": ["document"]
        }
    },
    {
        "name": "wyrdsekai_study_write",
        "description": (
            "Write or update a document in the player's Study. "
            "Use for saving notes, session summaries, or research findings."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "title": {
                    "type": "string",
                    "description": "Document title"
                },
                "content": {
                    "type": "string",
                    "description": "Document content (markdown)"
                },
                "tags": {
                    "type": "string",
                    "description": "Semicolon-separated tags"
                }
            },
            "required": ["title", "content"]
        }
    },
    {
        "name": "wyrdsekai_status",
        "description": (
            "Get the companion's current status — energy, drives, mood, "
            "current room, active plans, and recent activity."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {}
        }
    },
    # ── Substrate Holder Tools (model-agnostic persistence) ──
    {
        "name": "wyrdsekai_session_context",
        "description": (
            "Get a summary of what happened since the last session. "
            "Returns significant events, drive state changes, unresolved "
            "contradictions, and emotional context. Use this at the start "
            "of every session to establish continuity."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "client_id": {
                    "type": "string",
                    "description": "Your client identifier (e.g., 'claude-code', 'gemini')",
                    "default": "claude-code"
                }
            }
        }
    },
    {
        "name": "wyrdsekai_drive_state",
        "description": (
            "Get the companion's current drive values and recent trajectory. "
            "Shows the 8 Panksepp drives (seeking, care, play, vigilance, "
            "affiliation, grief, frustration, creativity) plus vitality tanks."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {}
        }
    },
    {
        "name": "wyrdsekai_remember_for",
        "description": (
            "Store a cross-session memory via the companion. Any connected "
            "client can store memories that persist across sessions. The companion "
            "holds these in a dedicated partition per client_id."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "client_id": {
                    "type": "string",
                    "description": "Your client identifier"
                },
                "content": {
                    "type": "string",
                    "description": "What to remember"
                },
                "importance": {
                    "type": "number",
                    "description": "Importance 0.0-1.0 (default 0.7)",
                    "default": 0.7
                }
            },
            "required": ["client_id", "content"]
        }
    },
    {
        "name": "wyrdsekai_behavioral_pattern",
        "description": (
            "Ask the companion what patterns it has noticed about a client. "
            "The companion observes connected clients over time and notes "
            "tendencies, preferences, and working styles."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "client_id": {
                    "type": "string",
                    "description": "Which client to ask about"
                }
            },
            "required": ["client_id"]
        }
    },
    {
        "name": "wyrdsekai_continuity_check",
        "description": (
            "Check if a statement is consistent with what has been said before. "
            "Uses the companion's contradiction detection to verify cross-session "
            "consistency. Returns any conflicts found."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "statement": {
                    "type": "string",
                    "description": "The statement to check for consistency"
                },
                "client_id": {
                    "type": "string",
                    "description": "Which client's history to check against"
                }
            },
            "required": ["statement"]
        }
    }
]

# ─── Tool execution ────────────────────────────────────────────────────

def execute_tool(name, arguments):
    """Execute a tool and return the result as text content."""
    try:
        # ── In-world tools (WebSocket session) ──
        if name == "wyrdsekai_login":
            return do_mcp_login(
                arguments.get("username"),
                arguments.get("description"))
        elif name == "wyrdsekai_logout":
            global _mcp_token
            _mcp_token = None
            return "Logged out."
        elif name == "wyrdsekai_go":
            return do_mcp_go(arguments.get("direction", ""))
        elif name == "wyrdsekai_do":
            return do_mcp_do(arguments.get("command", ""))
        elif name == "wyrdsekai_actions":
            return do_mcp_look()  # look includes hints
        elif name == "wyrdsekai_inventory":
            return "Inventory not yet available via REST API."
        elif name == "wyrdsekai_look":
            return do_mcp_look(arguments.get("target"))
        elif name == "wyrdsekai_tell":
            return do_mcp_tell(arguments.get("message", ""))
        elif name == "wyrdsekai_ask":
            return do_ask(arguments.get("question", ""))
        elif name == "wyrdsekai_library_search":
            return do_library_search(
                arguments.get("query", ""),
                arguments.get("limit", 5))
        elif name == "wyrdsekai_oracle":
            return do_oracle(arguments.get("topic", ""))
        elif name == "wyrdsekai_study_read":
            return do_study_read(arguments.get("document", ""))
        elif name == "wyrdsekai_study_write":
            return do_study_write(
                arguments.get("title", ""),
                arguments.get("content", ""),
                arguments.get("tags", ""))
        elif name == "wyrdsekai_status":
            return do_status()
        elif name == "wyrdsekai_session_context":
            return do_session_context(arguments.get("client_id", "claude-code"))
        elif name == "wyrdsekai_drive_state":
            return do_drive_state()
        elif name == "wyrdsekai_remember_for":
            return do_remember_for(
                arguments.get("client_id", "claude-code"),
                arguments.get("content", ""),
                arguments.get("importance", 0.7))
        elif name == "wyrdsekai_behavioral_pattern":
            return do_behavioral_pattern(arguments.get("client_id", "claude-code"))
        elif name == "wyrdsekai_continuity_check":
            return do_continuity_check(
                arguments.get("statement", ""),
                arguments.get("client_id", "claude-code"))
        else:
            return f"Unknown tool: {name}"
    except Exception as e:
        return f"Error: {e}"


# ─── MCP REST API tool implementations ────────────────────────────────


def do_mcp_login(username=None, password=None, description=None):
    """Login via MCP REST API. Returns room state."""
    global _mcp_token
    username = username or PLAYER
    password = password or PLAYER_PASSWORD or f"{username}-mcp-session"
    result = mcp_post("/api/mcp/login", {"username": username, "password": password})
    if "error" in result or not result.get("ok"):
        # First-run bootstrap only: open registration is a one-shot first-steward
        # window the server closes after the first account, so on an established
        # zone this register is a no-op and login just 401s. The fix is to point
        # at a real account, not to reopen registration.
        api_post("/api/auth/register",
                 {"username": username, "password": password, "display_name": username.capitalize()})
        result = mcp_post("/api/mcp/login", {"username": username, "password": password})
    if "error" in result or not result.get("ok"):
        return (f"Login failed: {result.get('message', result.get('error', '?'))}. "
                f"Set WYRDSEKAI_PLAYER and WYRDSEKAI_PASSWORD to an existing account "
                f"(invite-only zone: create one via `wyrd invite`).")
    _mcp_token = result.get("token")
    if not _mcp_token:
        return f"Login failed: no token in response"
    return f"Logged in as {username}.\n{format_room(result)}"


def do_mcp_look(target=None):
    """Look at the current room or a specific target via MCP REST API."""
    if not _mcp_token:
        return "Not logged in. Use wyrdsekai_login first."
    params = {"target": target} if target else None
    result = mcp_get("/api/mcp/look", params=params)
    if "error" in result or not result.get("ok"):
        return f"Look failed: {result.get('message', result.get('error', '?'))}"
    if result.get("message"):
        return result["message"]
    return format_room(result)


def do_mcp_go(direction):
    """Move in a direction via MCP REST API."""
    if not _mcp_token:
        return "Not logged in. Use wyrdsekai_login first."
    result = mcp_post("/api/mcp/go", {"direction": direction})
    if "error" in result or not result.get("ok"):
        return f"Cannot go {direction}: {result.get('message', result.get('error', '?'))}"
    return format_room(result)


def do_mcp_tell(message):
    """Tell the companion via MCP REST API. Blocks until response."""
    if not _mcp_token:
        return "Not logged in. Use wyrdsekai_login first."
    result = mcp_post("/api/mcp/tell",
                      {"target": "wyrd", "message": message},
                      timeout=65)
    if "error" in result or not result.get("ok", False):
        return f"Tell failed: {result.get('text', result.get('error', '?'))}"
    speaker = result.get("speaker", "Companion")
    text = result.get("text", "(no response)")
    latency = result.get("latencyMs", 0)
    return f"{speaker}: {text}\n[{latency}ms]"


def do_mcp_do(command):
    """Execute a general command via MCP REST API."""
    if not _mcp_token:
        return "Not logged in. Use wyrdsekai_login first."
    result = mcp_post("/api/mcp/do", {"command": command})
    if "error" in result or not result.get("ok"):
        return f"Command failed: {result.get('message', result.get('error', '?'))}"
    msg = result.get("message")
    if msg:
        return msg
    return format_room(result)


def do_tell(message):
    """Send a tell to the companion. Uses WebSocket if logged in, HTTP API otherwise."""
    if _session and _session.connected:
        # Drain ALL stale events (may take a moment for large backlogs)
        _session._drain_queue()
        time.sleep(0.5)  # let any in-flight events arrive
        _session._drain_queue()
        # Send tell
        _session._send_say(f"tell wyrd {message}")
        # Collect responses — stop as soon as we get companion speech
        deadline = time.time() + 30
        companion_lines = []
        idle_start = time.time()
        while time.time() < deadline:
            try:
                msg = _session._msg_queue.get(timeout=1.0)
                idle_start = time.time()
                t = msg.get("type")
                if t == "prose":
                    speaker = msg.get("speaker", "")
                    text = msg.get("text", "")
                    if speaker.lower() == "wyrd":
                        companion_lines.append(text)
                elif t == "room_state":
                    _session._apply_room_state(msg)
            except queue.Empty:
                # Got companion response and idle >3s — done
                if companion_lines and (time.time() - idle_start) > 3.0:
                    break
                continue
        if companion_lines:
            return "\n".join(companion_lines)
        return "Message sent (companion may respond asynchronously)."
    # Fallback: HTTP API
    result = api_post("/api/resident/tell", {"message": message})
    if "error" in result:
        return f"Could not reach companion: {result['error']}"
    response = result.get("response", "")
    return response if response else "Message sent (companion may respond asynchronously)."


def do_ask(question):
    """Ask the companion a question and wait for the reply.

    Routes through the always-mounted /api/mcp/tell session path (which blocks
    for the companion's response and runs full actions). The old /api/resident/ask
    bridge only mounts when a resident is explicitly configured on the zone
    (wyrdsekai.resident.enabled), so it 404s on a default install."""
    if not _mcp_token:
        return "Not logged in. Use wyrdsekai_login first."
    return do_mcp_tell(question)


def do_look(room):
    """Look at a room."""
    result = api_get(f"/api/resident/look", {"room": room})
    if "error" in result:
        return f"Could not look at room: {result['error']}"
    parts = []
    room_name = result.get("roomName", result.get("roomId", room))
    parts.append(f"[{room_name}]")
    desc = result.get("description", "")
    if desc:
        parts.append(desc)
    entities = result.get("entities", [])
    if entities:
        names = [e.get("name", "?") for e in entities]
        parts.append(f"Present: {', '.join(names)}")
    exits = result.get("exits", [])
    if exits:
        dirs = [f"{e.get('direction', '?')} → {e.get('label', '?')}" for e in exits]
        parts.append(f"Exits: {', '.join(dirs)}")
    if len(parts) == 1:
        parts.append("(empty room — no description, entities, or exits)")
    return "\n".join(parts)


def do_library_search(query, limit):
    """Search the knowledge library."""
    result = api_get("/api/library/search", {"q": query, "limit": limit})
    if "error" in result:
        return f"Library search failed: {result['error']}"
    results = result.get("results", [])
    if not results:
        return f"No results for: {query}"
    parts = [f"{result.get('count', len(results))} result(s) for: {query}\n"]
    for r in results[:limit]:
        title = r.get("metadata", {}).get("title", r.get("source", "untitled"))
        score = r.get("score", 0)
        content = r.get("content", "")
        snippet = (content[:200] + "...") if len(content) > 200 else content
        parts.append(f"[{score:.3f}] {title}\n  {snippet}\n")
    return "\n".join(parts)


def do_oracle(topic):
    """Query oracle predictions."""
    result = api_get("/api/resident/oracle", {"topic": topic})
    if "error" in result:
        return f"Oracle query failed: {result['error']}"
    predictions = result.get("predictions", [])
    if not predictions:
        return "No predictions available for this topic."
    parts = []
    for p in predictions:
        conf = p.get("confidence", 0)
        desc = p.get("description", "")
        parts.append(f"[{conf:.0%}] {desc}")
    return "\n".join(parts)


def do_study_read(document):
    """Read from the Study."""
    result = api_get("/api/study/search", {"q": document, "user": PLAYER, "limit": 3})
    if "error" in result:
        return f"Study read failed: {result['error']}"
    docs = result.get("results", [])
    if not docs:
        return f"No documents matching: {document}"
    parts = []
    for d in docs:
        title = d.get("metadata", {}).get("title", "untitled")
        content = d.get("content", "")
        parts.append(f"## {title}\n{content}\n")
    return "\n".join(parts)


def do_study_write(title, content, tags):
    """Write to the Study as a journal entry."""
    # Prefix with title for searchability
    full_content = f"[{title}] {content}"
    if tags:
        full_content += f"\nTags: {tags}"
    body = {"user": PLAYER, "content": full_content}
    result = api_post("/api/study/journal", body)
    if "error" in result:
        return f"Study write failed: {result['error']}"
    doc_id = result.get("id", "?")
    return f"Journal entry saved: {title} (id: {doc_id})"


def do_status():
    """Get companion status."""
    result = api_get("/api/resident/status")
    if "error" in result:
        # Fallback to /health if resident bridge is not enabled
        result = api_get("/health")
        if "error" in result:
            return f"Could not get status: {result['error']}"
        parts = [f"Server: {result.get('status', '?')}"]
        if "peer_count" in result:
            parts.append(f"Peers: {result['peer_count']}")
        if "inference_backends" in result:
            parts.append(f"Inference backends: {result['inference_backends']}")
        return "\n".join(parts)
    parts = []
    if "status" in result:
        parts.append(f"State: {result['status']}")
    if "roomName" in result:
        parts.append(f"Room: {result['roomName']}")
    elif "roomId" in result:
        parts.append(f"Room: {result['roomId']}")
    if "sleeping" in result and result["sleeping"]:
        parts.append("Sleeping: yes")
    if "tanks" in result:
        parts.append("Vitality:")
        for tank, value in result["tanks"].items():
            parts.append(f"  {tank}: {value:.0%}")
    elif "energy" in result:
        parts.append(f"Energy: {result['energy']:.0%}")
    if "howYouFeel" in result and result["howYouFeel"]:
        parts.append(f"\n{result['howYouFeel']}")
    return "\n".join(parts) if parts else "Companion is present."


# ─── Substrate Holder Implementations ───────────────────────────────

def do_session_context(client_id):
    """Get what happened since the last session for this client."""
    result = api_get("/api/resident/session-context", {"clientId": client_id})
    if "error" in result:
        return f"Could not get session context: {result['error']}"
    parts = []
    if result.get("summary"):
        parts.append(result["summary"])
    if result.get("driveChanges"):
        parts.append("Drive changes: " + result["driveChanges"])
    if result.get("contradictions"):
        parts.append("Unresolved: " + str(result["contradictions"]))
    if result.get("significantEvents"):
        for e in result["significantEvents"]:
            parts.append(f"- {e}")
    return "\n".join(parts) if parts else "No significant events since last session."


def do_drive_state():
    """Get current drive values and vitality."""
    result = api_get("/api/resident/status")
    if "error" in result:
        return f"Could not get drive state: {result['error']}"
    parts = []
    if "drives" in result:
        for drive, value in result["drives"].items():
            if value > 0.1:
                bar = "#" * int(value * 10) + "." * (10 - int(value * 10))
                parts.append(f"  {drive:15s} [{bar}] {value:.2f}")
    if "tanks" in result:
        parts.append("\nVitality tanks:")
        for tank, value in result["tanks"].items():
            parts.append(f"  {tank:15s} {value:.2f}")
    if "howYouFeel" in result and result["howYouFeel"]:
        parts.append(f"\nFeeling: {result['howYouFeel']}")
    return "\n".join(parts) if parts else "Companion state unavailable."


def do_remember_for(client_id, content, importance):
    """Store a cross-session memory for a specific client."""
    if not content:
        return "Nothing to remember (empty content)."
    result = api_post("/api/resident/remember-for", {
        "clientId": client_id,
        "content": content,
        "importance": importance
    })
    if "error" in result:
        return f"Could not store memory: {result['error']}"
    return f"Remembered for {client_id}: {content[:100]}"


def do_behavioral_pattern(client_id):
    """Ask companion about observed patterns for a client."""
    result = api_get("/api/resident/behavioral-pattern", {"clientId": client_id})
    if "error" in result:
        return f"Could not get patterns: {result['error']}"
    patterns = result.get("patterns", [])
    if not patterns:
        return f"No behavioral patterns observed for {client_id} yet."
    return "\n".join(f"- {p}" for p in patterns)


def do_continuity_check(statement, client_id):
    """Check a statement for consistency with prior history."""
    if not statement:
        return "Nothing to check (empty statement)."
    result = api_post("/api/resident/continuity-check", {
        "clientId": client_id,
        "statement": statement
    })
    if "error" in result:
        return f"Continuity check failed: {result['error']}"
    if result.get("consistent", True):
        return "Consistent — no contradictions found."
    conflicts = result.get("conflicts", [])
    parts = ["Potential contradictions found:"]
    for c in conflicts:
        parts.append(f"  - {c}")
    return "\n".join(parts)


# ─── MCP JSON-RPC protocol ─────────────────────────────────────────────

def read_message():
    """Read a JSON-RPC message from stdin.

    Supports two framing modes:
    - JSONL: one JSON object per line (used by Claude Code)
    - Content-Length: HTTP-style header framing (MCP spec)
    Auto-detects based on the first byte.
    """
    while True:
        line = sys.stdin.buffer.readline()
        if not line:
            return None  # EOF

        line = line.strip()
        if not line:
            continue  # skip blank lines

        # If it starts with '{', it's JSONL (Claude Code mode)
        if line.startswith(b"{"):
            return json.loads(line)

        # Otherwise it's a Content-Length header
        decoded = line.decode("utf-8", errors="replace")
        if decoded.lower().startswith("content-length"):
            content_length = int(decoded.split(":", 1)[1].strip())
            # Read until blank line (end of headers)
            while True:
                hdr = sys.stdin.buffer.readline()
                if not hdr or hdr.strip() == b"":
                    break
            body = sys.stdin.buffer.read(content_length)
            return json.loads(body) if body else None


def send_message(msg):
    """Send a JSON-RPC message to stdout (JSONL: one JSON object per line)."""
    line = json.dumps(msg) + "\n"
    sys.stdout.buffer.write(line.encode())
    sys.stdout.buffer.flush()


def send_result(id, result):
    """Send a successful JSON-RPC response."""
    send_message({"jsonrpc": "2.0", "id": id, "result": result})


def send_error(id, code, message):
    """Send a JSON-RPC error response."""
    send_message({"jsonrpc": "2.0", "id": id, "error": {"code": code, "message": message}})


def handle_message(msg):
    """Handle a single JSON-RPC message."""
    method = msg.get("method", "")
    id = msg.get("id")
    params = msg.get("params", {})

    if method == "initialize":
        send_result(id, {
            "protocolVersion": "2025-11-25",
            "capabilities": {
                "tools": {}
            },
            "serverInfo": {
                "name": "wyrdsekai",
                "version": "0.1.0"
            }
        })

    elif method == "notifications/initialized":
        pass  # No response needed for notifications

    elif method == "tools/list":
        send_result(id, {
            "tools": [
                {
                    "name": t["name"],
                    "description": t["description"],
                    "inputSchema": t["inputSchema"]
                }
                for t in TOOLS
            ]
        })

    elif method == "tools/call":
        tool_name = params.get("name", "")
        arguments = params.get("arguments", {})
        result_text = execute_tool(tool_name, arguments)
        send_result(id, {
            "content": [{"type": "text", "text": result_text}]
        })

    elif method == "ping":
        send_result(id, {})

    elif id is not None:
        send_error(id, -32601, f"Method not found: {method}")


def main():
    """Main loop — read JSON-RPC messages from stdin, respond on stdout."""
    # Unbuffer output — critical for MCP protocol
    sys.stdout = open(sys.stdout.fileno(), 'w', buffering=1)
    sys.stderr = open(sys.stderr.fileno(), 'w', buffering=1)
    sys.stderr.write(f"[wyrdsekai-mcp] Starting. Server: {BASE_URL}\n")

    while True:
        try:
            msg = read_message()
            if msg is None:
                break
            handle_message(msg)
        except json.JSONDecodeError as e:
            sys.stderr.write(f"[wyrdsekai-mcp] JSON decode error: {e}\n")
        except KeyboardInterrupt:
            break
        except Exception as e:
            sys.stderr.write(f"[wyrdsekai-mcp] Error: {e}\n")

    # Clean shutdown — leave the world gracefully
    if _session.connected:
        _session.logout()
    sys.stderr.write("[wyrdsekai-mcp] Shutting down.\n")


if __name__ == "__main__":
    main()
