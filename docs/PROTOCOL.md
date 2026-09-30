# Wyrdsekai Wire Protocol Reference

> Version: 1.0 (March 2026)
> Status: Stable for client implementation

This document specifies the wire protocol between a Wyrdsekai client and server. Any client that speaks this protocol is a valid Wyrdsekai client — CLI, mobile app, web browser, accessibility tool, bot, or anything else.

## Transport

| Property | Value |
|----------|-------|
| Transport | WebSocket |
| Path | `/ws` |
| Frame format | JSON text frames |
| Encoding | UTF-8 |
| Idle timeout | 5 minutes (implement ping/pong or periodic messages) |
| From another machine | `wss://<host>:7443/ws` and `https://<host>:7443/api/...` (encrypted) |
| On the same machine | `ws://127.0.0.1:7070/ws` and `http://127.0.0.1:7070/api/...` |

### Encryption and the household certificate

Every home makes its own certificate authority (CA) the first time it starts. The CA signs the
certificate that port 7443 serves. It also signs the certificate of the household bus (NATS on
4222 and the phones' websocket on 4223). The server always sends the whole chain: its own
certificate, then the household CA.

A client from another machine must check that chain before it sends anything:

1. Get the CA's fingerprint from the pairing invite. The invite field `home_ca_fp` is the SHA-256
   of the CA certificate (its DER bytes), in lowercase hex with no colons. The invite field
   `lan_https` is the address to use on the home network, for example `https://198.51.100.20:7443`.
2. On connect, find the certificate in the served chain whose SHA-256 is `home_ca_fp`.
3. Check that the server's own certificate is signed by that CA and is still valid.

A home's CA is never in a public trust store, so the platform's ordinary checks will refuse it.
That is expected: the fingerprint from the invite is what the client trusts. The server's
certificate names this machine's host name, `<host name>.local` and every network address it has,
but a client that pins the CA does not need to match names. If the fingerprint does not match,
refuse and tell the person to pair again. Do not offer to trust a new certificate.

Port 7070 answers this machine only, without encryption. Local tools and the browser on the same
machine use it. `WYRDSEKAI_HTTP_LAN_PLAINTEXT=true` opens it to the network again for phone apps
from before 0.5.0 (see CONFIGURATION.md).

## Authentication

### Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| `POST` | `/api/auth/register` | Create account |
| `POST` | `/api/auth/login` | Login |
| `POST` | `/api/auth/logout` | Logout |
| `GET` | `/api/auth/me` | Current user info |

### Register

**Request**:
```json
POST /api/auth/register
Content-Type: application/json

{
  "username": "alice",
  "password": "secret123",
  "display_name": "Alice"
}
```

**Response** (201):
```json
{
  "token": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "user_id": "uuid-string",
  "username": "alice"
}
```

### Login

**Request**:
```json
POST /api/auth/login
Content-Type: application/json

{
  "username": "alice",
  "password": "secret123"
}
```

**Response** (200): Same shape as register.

### Token

The token is an opaque UUID string. It is NOT a JWT — do not attempt to decode it. Tokens expire after 7 days.

Pass the token to the WebSocket connection as a query parameter:
```
wss://<host>:7443/ws?token=<token>
```
On the same machine, `ws://127.0.0.1:7070/ws?token=<token>` works too.

For HTTP endpoints, use either:
- Query parameter: `GET /api/auth/me?token=<token>`
- Header: `Authorization: Bearer <token>`

---

## WebSocket Connection

### Connecting

```
wss://<host>:7443/ws?token=<session-token>
```

On successful connection, the server sends a `room_state` message with the player's current room and inventory.

### Connection Variants

| Query Parameter | Behavior |
|----------------|----------|
| `token=<session-token>` | Authenticated user, starts in last room (or "nexus") |
| `transit_token=<transit-token>` | Federated visitor, starts in "docks" room |
| (none) | Anonymous/development mode only |

### Close Codes

| Code | Meaning |
|------|---------|
| 4001 | Invalid or expired session token |
| 4003 | Invalid or expired transit token |

---

## Message Format

Every message is a JSON object with a `type` field that determines its shape.

**Client → Server (C2S)**: Every message has an `id` field (string) for request/response correlation. Generate unique IDs per message (UUID recommended).

**Server → Client (S2C)**: Every message has a `seq` field (long integer), monotonically increasing per session. Used for reconnection replay.

---

## Client → Server Messages (C2S)

### `say` — Speak or act

```json
{
  "type": "say",
  "id": "req-1",
  "roomId": "nexus",
  "text": "Hello everyone!"
}
```

The primary input message. Text is processed by the room's companion/agent. Natural language and MUD-style commands both work.

### `go` — Navigate

```json
{
  "type": "go",
  "id": "req-2",
  "roomId": "nexus",
  "direction": "north"
}
```

Move to an adjacent room. Direction must match an available exit.

### `look` — Observe

```json
{
  "type": "look",
  "id": "req-3",
  "roomId": "nexus"
}
```

Re-examine the current room. Server responds with a fresh `room_state`.

### `take` — Pick up object

```json
{
  "type": "take",
  "id": "req-4",
  "roomId": "nexus",
  "objectName": "scroll"
}
```

### `drop` — Drop object

```json
{
  "type": "drop",
  "id": "req-5",
  "roomId": "nexus",
  "objectName": "scroll"
}
```

### `use` — Use object

```json
{
  "type": "use",
  "id": "req-6",
  "roomId": "nexus",
  "objectName": "key",
  "target": "locked_door"
}
```

`target` is optional (nullable). Omit for objectsused without a target.

### `hint_select` — Select a hint

```json
{
  "type": "hint_select",
  "id": "req-7",
  "roomId": "nexus",
  "index": 0
}
```

Select a hint by zero-based index from the most recent hint list. This is the "one-keystroke action" path — pressing `[1]` selects hint 0.

### `reconnect` — Request replay

```json
{
  "type": "reconnect",
  "id": "req-8",
  "roomId": "nexus",
  "lastSeenSeq": 42
}
```

After a disconnect, send this to replay all messages with `seq > lastSeenSeq`. See [Reconnection](#reconnection) below.

### `command` — System/zone command

```json
{
  "type": "command",
  "id": "req-9",
  "command": "inventory",
  "args": [],
  "payload": {}
}
```

Used for system commands (`who`, `inventory`, `help`) and zone-type actions. Zone-type commands use namespaced names:

```json
{
  "type": "command",
  "id": "req-10",
  "command": "codezaiku.approve",
  "args": [],
  "payload": {
    "eventId": "evt-42",
    "decision": "approve"
  }
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `command` | string | yes | Command name. Namespaced (`zone.action`) for zone-type commands. |
| `args` | string[] | yes | Positional arguments (e.g., `["alice", "enter"]` for `/grant alice enter`) |
| `payload` | map<string,string> | no | Structured key-value data for zone-type actions. Defaults to `{}`. |

### `set_preference` — Client preference

```json
{
  "type": "set_preference",
  "id": "req-11",
  "key": "locale",
  "value": "es"
}
```

Known preference keys:

| Key | Values | Effect |
|-----|--------|--------|
| `locale` | BCP 47 tag (e.g., `"en"`, `"es"`, `"ja"`) | Server responds in requested language |

---

## Server → Client Messages (S2C)

### `room_state` — Full room snapshot

Sent on room entry, after `look`, or during reconnect replay.

```json
{
  "type": "room_state",
  "seq": 1,
  "room": { ... },
  "inventory": [ ... ]
}
```

**`room`** — [RoomSnapshot](#roomsnapshot):

```json
{
  "roomId": "nexus",
  "name": "The Nexus",
  "description": "A vast crystalline chamber hums with quiet energy.",
  "zone": "home",
  "exits": [
    { "direction": "north", "targetRoom": "terminal", "label": "A corridor leads north to the Terminal" }
  ],
  "entities": [
    { "id": "agent-1", "name": "Guide", "type": "agent", "description": "A patient guide" }
  ],
  "objects": [
    { "id": "obj-1", "name": "scroll", "description": "An ancient scroll", "takeable": true }
  ],
  "hints": [
    { "label": "Talk to the guide", "intent": "greet", "action": "say", "labelKey": null }
  ]
}
```

**`inventory`** — List of [RoomObject](#roomobject) the player is carrying. May be `null` (no inventory update).

### `prose` — Narration, speech, descriptions

The primary output message. Carries narrative text plus optional structured data.

```json
{
  "type": "prose",
  "seq": 2,
  "speaker": "Guide",
  "text": "Welcome, traveler. The Nexus connects all rooms in this zone.",
  "hints": [
    { "label": "Ask about rooms", "intent": "ask_rooms", "action": "say", "labelKey": null }
  ],
  "structured": null,
  "priority": "normal",
  "lang": "en",
  "isAiGenerated": true,
  "blocks": []
}
```

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `speaker` | string | no | Who is speaking: agent name, `"narrator"`, or `"system"` |
| `text` | string | no | The prose text |
| `hints` | [Hint](#hint)[] | yes | Contextual action suggestions |
| `structured` | [Structured](#structured) | yes | Machine-parseable room data (accessibility) |
| `priority` | string | no | `"critical"`, `"normal"`, or `"ambient"` |
| `lang` | string | yes | BCP 47 language tag. `null` = English assumed. |
| `isAiGenerated` | boolean | no | `true` if generated by AI (EU AI Act compliance) |
| `blocks` | [ContentBlock](#contentblock)[] | no | Zone-type-specific content blocks. Empty list if none. |

**Priority handling**:
- `critical` — Show immediately, with emphasis (bell, bold, red)
- `normal` — Standard display
- `ambient` — Background narration. May be suppressed in non-verbose mode.

### `agent_action` — Visible agent action

```json
{
  "type": "agent_action",
  "seq": 3,
  "agentName": "Guide",
  "action": "take",
  "description": "picks up the ancient scroll"
}
```

### `state_change` — Room state mutation

```json
{
  "type": "state_change",
  "seq": 4,
  "description": "The northern door swings open.",
  "structured": null,
  "blocks": []
}
```

Signals that the room state has changed. Client should expect a fresh `room_state` or use the description/structured data to update locally.

### `replay_done` — Reconnection replay complete

```json
{
  "type": "replay_done",
  "seq": 99,
  "fromSeq": 42,
  "toSeq": 99,
  "count": 57
}
```

Sent after replaying all missed messages. Client is now caught up.

### `error` — Error response

```json
{
  "type": "error",
  "seq": 5,
  "code": "no_exit",
  "message": "There is no exit in that direction.",
  "requestId": "req-2"
}
```

`requestId` correlates to the C2S message `id` that caused the error.

**Common error codes**:

| Code | Meaning |
|------|---------|
| `no_exit` | No exit in that direction |
| `object_not_found` | Named object not in room or inventory |
| `ward_denied` | Permission denied by ward system |
| `not_takeable` | Object cannot be picked up |
| `UNKNOWN_COMMAND` | Unrecognized command name |

### `notification` — System notification

```json
{
  "type": "notification",
  "seq": 6,
  "level": "info",
  "title": "Welcome",
  "message": "Connected to Wyrdsekai home zone."
}
```

| Level | Meaning |
|-------|---------|
| `info` | Informational |
| `warning` | Something needs attention |
| `error` | System-level error |

### `transit` — Federation redirect

```json
{
  "type": "transit",
  "seq": 7,
  "targetZoneId": "neighbor-zone",
  "targetUrl": "wss://neighbor.example.com/ws",
  "transitToken": "transit-token-string",
  "message": "Departing for neighbor-zone. Token expires in 1 hour."
}
```

Client should:
1. Close current WebSocket
2. Connect to `targetUrl` with `?transit_token=<transitToken>`
3. Receive `room_state` for the destination zone's docks

### `token_stream` — Real-time token streaming

```json
{
  "type": "token_stream",
  "seq": 8,
  "source": "Guide",
  "token": "The ancient",
  "done": false,
  "context": null
}
```

Lightweight message for token-by-token delivery during AI inference. Multiple `token_stream` messages arrive in sequence:

```json
{"type": "token_stream", "seq": 8,  "source": "Guide", "token": "The ancient",    "done": false, "context": null}
{"type": "token_stream", "seq": 9,  "source": "Guide", "token": " scroll reads",  "done": false, "context": null}
{"type": "token_stream", "seq": 10, "source": "Guide", "token": ": 'Welcome.'",   "done": true,  "context": null}
```

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `source` | string | no | Speaker identity (companion name or agent ID) |
| `token` | string | no | Text fragment |
| `done` | boolean | no | `true` on final token — assemble full text |
| `context` | string | yes | Optional routing context (e.g., room ID). `null` for common case. |

**Client implementation**: Append each `token` to a buffer. On `done=true`, finalize as a complete message. Animate typing if desired.

---

## Model Types

### RoomSnapshot

| Field | Type | Description |
|-------|------|-------------|
| `roomId` | string | Room identifier (sharding key) |
| `name` | string | Display name |
| `description` | string | Current room description |
| `zone` | string | Zone identifier |
| `exits` | Exit[] | Available exits |
| `entities` | Entity[] | Agents/players present |
| `objects` | RoomObject[] | Interactive objects in room |
| `hints` | Hint[] | Contextual action suggestions |

### Exit

| Field | Type | Description |
|-------|------|-------------|
| `direction` | string | Direction label: `"north"`, `"up"`, `"portal"`, etc. |
| `targetRoom` | string | Target room ID |
| `label` | string | Human-readable description |

### Entity

| Field | Type | Description |
|-------|------|-------------|
| `id` | string | Entity ID |
| `name` | string | Display name |
| `type` | string | `"player"`, `"agent"`, or `"npc"` |
| `description` | string | Brief description |

### RoomObject

| Field | Type | Description |
|-------|------|-------------|
| `id` | string | Object ID |
| `name` | string | Display name |
| `description` | string | Brief description |
| `takeable` | boolean | Whether the object can be picked up |

### Hint

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `label` | string | no | Display text (e.g., "Talk to the guide") |
| `intent` | string | no | Semantic intent for the system |
| `action` | string | no | Action type: `"say"`, `"go"`, `"use"`, `"take"`, `"look"`, `"command"` |
| `labelKey` | string | yes | i18n key for localization |

### Structured

Machine-parseable room data for accessibility clients. Mirrors RoomSnapshot with additional metadata.

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `name` | string | yes | Room name |
| `description` | string | yes | Room description |
| `exits` | Exit[] | yes | Available exits |
| `entities` | Entity[] | yes | Entities present |
| `objects` | RoomObject[] | yes | Interactive objects |
| `hints` | Hint[] | yes | Contextual hints |
| `properties` | map<string,string> | yes | Metadata key-value pairs |
| `zone` | string | yes | Zone identifier |

### ContentBlock

Zone-type-specific content for rich client rendering. Clients render blocks they understand; fall back to `fallback` text for unknown formats.

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `format` | string | no | Namespaced type identifier (e.g., `"codezaiku.diff"`, `"wyrdsekai.room"`) |
| `data` | object | no | Arbitrary JSON payload specific to the format |
| `fallback` | string | no | Prose text for clients that don't understand this format |

**Built-in formats** (all clients should handle these, or use fallback):

| Format | Description |
|--------|-------------|
| `wyrdsekai.room` | Room state data |
| `wyrdsekai.inventory` | Inventory list |
| `wyrdsekai.hint_group` | Hint chip group |

Unknown formats are expected. Always render `fallback` text when you don't recognize a format.

---

## Reconnection

Wyrdsekai uses sequence-number-based replay for seamless reconnection.

### Protocol

1. Client tracks the highest `seq` received from the server
2. On disconnect, client reconnects to the same WebSocket URL with the same token
3. Client sends `reconnect` with `lastSeenSeq` set to the highest seq it received
4. Server replays all messages with `seq > lastSeenSeq`
5. Server sends `replay_done` when caught up
6. Client resumes normal operation

### Deduplication

If the client receives a message with a `seq` it has already seen (possible during reconnection race), it should silently discard the duplicate.

### Example

```
Client connected, receives seq 1..50
  ↓ (network drops)
Client reconnects
Client sends: {"type": "reconnect", "id": "r-1", "roomId": "nexus", "lastSeenSeq": 50}
Server replays: seq 51, 52, 53, 54, 55
Server sends: {"type": "replay_done", "seq": 55, "fromSeq": 50, "toSeq": 55, "count": 5}
Client is caught up
```

### TokenStream During Replay

On reconnect, the server may replay individual `token_stream` messages or collapse a completed token stream into a single `prose` message. Clients should handle both.

---

## Federation (Cross-Zone Transit)

Wyrdsekai zones can federate — a player can visit another zone by transiting through a Gate.

### Flow

1. Player interacts with a Gate object or uses a transit command
2. Server validates transit eligibility and issues a transit token
3. Server sends `transit` message with destination URL and token
4. Client disconnects from current zone
5. Client connects to destination: `wss://target-host/ws?transit_token=<token>`
6. Destination checks the token against the one it issued. The token must be
   for this zone, unexpired and unused, and the destination must still hold an
   active agreement with the zone it issued the token to. A token works once.
   Ending the agreement cancels every token issued under it.
7. Player appears in the destination zone's "docks" room
8. Player receives `room_state` and can interact normally

The token is a random identifier the destination issued and stored. It is not
signed; the destination trusts its own record of it. The transit request and
response that carry it between the zones are signed envelopes.

### Trust Levels

| Level | Token lasts | Issued when the agent has |
|-------|-------------|---------------------------|
| `tourist` | 1 hour | fewer than 3 earlier visits |
| `resident` | 24 hours | 3 to 9 earlier visits |
| `citizen` | 7 days | 10 or more earlier visits |

Visits are counted by the destination since it last started. The level sets how long a token lasts. It does not change what a visitor may
do: every visitor arrives at the docks as a guest, without an account.

---

## Through a Relay: Sealed Requests

A phone (or the `wyrd` terminal) that reaches its home through a relay does not
use the HTTP endpoints above. It sends one NATS request and gets one reply, on
these subjects:

| Subject | What it carries |
|---------|-----------------|
| `wyrd.zone.{zone}.mcp.login` | name and password; the reply has the session token |
| `wyrd.zone.{zone}.auth.register`, `.auth.redeem` | name, password, invite code; the reply has the token |
| `wyrd.zone.{zone}.auth.status` | whether the home takes new accounts |
| `wyrd.zone.{zone}.mcp.tell` | a tell to a companion or a person |
| `wyrd.zone.{zone}.library.search` | a library search and its results |
| `wyrd.zone.{zone}.study.journal` | a journal entry, or your recent entries (private ones included) |
| `wyrd.zone.{zone}.pair.device` | the new device token |
| `wyrd.zone.{zone}.account.zonebank.get`, `.put` | your list of homes |
| `wyrd.zone.{zone}.directory.search`, `.knock`, `.knock.list` | finding a zone, asking to join, the steward's list of requests |

Since 0.5.0 every one of these is **sealed**: encrypted from the phone to the
home, so the relay passes it on without being able to read it or answer in the
home's place. The key the phone seals to is the home's tunnel key, `zk` in the
pairing invite (32 bytes, base64url). All base64 below is base64url without
padding.

**Request.** For each request the phone makes a fresh X25519 key pair `e` and 16
random bytes `n`. Then:

```
dh    = X25519(e.private, zk)
prk   = HKDF-Extract(salt = n, dh)                          (HKDF with SHA-256)
okm   = HKDF-Expand(prk, "wyrd-request-v2" || e.public, 64)
k_req = okm[0..32]      k_rep = okm[32..64]
plain = {"ts": <milliseconds since 1970>, "body": <the request object>}
c     = ChaCha20-Poly1305(k_req, nonce = 12 zero bytes, aad = the subject, plain)
```

The phone sends `{"v":2,"e":"<e.public>","n":"<n>","c":"<c>"}`. The request
object is what the phone sent before sealing existed, for example
`{"username":"alice","password":"..."}`. An empty request is `{}`.

**Reply.** The home seals its answer with `k_rep`, the same zero nonce and the
same subject as associated data, and sends `{"v":2,"c":"<c>"}`. Each key seals
exactly one message, which is why a zero nonce is safe.

**Refusals.** These come back unsealed, and carry no detail:

| Reply | Meaning |
|-------|---------|
| `{"ok":false,"error":"sealed_refused"}` | The request did not open (wrong key, altered, or sealed for another subject), its `ts` is more than 120 seconds from the home's clock, or its `n` was already used in the last 10 minutes. Make a new request; check the device clock. |
| `{"ok":false,"error":"sealed_required"}` | An unsealed request reached the home through a relay. The steward can accept these for a while with `WYRDSEKAI_RELAY_ALLOW_PLAINTEXT_REQUESTS=true`. |

A client must treat any reply that is not sealed, other than these two
refusals, as not from its home.

On the home's own network the home still accepts unsealed requests.
`wyrd.discover.zone` (it answers with the zone's name only) is never sealed.

**Test vectors.** `core/src/test/resources/crypto/request-v2-vectors.json`
holds fixed keys, one request and one reply, byte for byte. Every
implementation should reproduce them exactly; the home's own tests do.

---

## Implementation Checklist

Minimum viable client implementation:

- [ ] HTTP auth (register + login → token)
- [ ] WebSocket connection with token
- [ ] Send: `say`, `go`, `look`, `take`, `drop`, `use`
- [ ] Receive: `room_state`, `prose`, `error`
- [ ] Display room name, description, exits, entities, objects
- [ ] Display prose text with speaker attribution
- [ ] Display hints as selectable options
- [ ] Send `hint_select` when user selects a hint
- [ ] Track `seq` for reconnection
- [ ] Send `reconnect` after disconnect
- [ ] Handle `replay_done`
- [ ] Display `notification` messages
- [ ] Handle unknown message types gracefully (log and skip)
- [ ] Handle unknown `ContentBlock` formats (render `fallback` text)

### Nice to have

- [ ] `token_stream` rendering (animated typing)
- [ ] `command` with `payload` for zone-type actions
- [ ] `transit` handling (cross-zone navigation)
- [ ] Priority-based rendering (critical=bold, ambient=dim/suppressed)
- [ ] `structured` data for accessibility mode
- [ ] `isAiGenerated` disclosure
- [ ] `lang` for i18n / right-to-left layout
- [ ] `set_preference` for locale

---

## Conformance Testing

The `protocol-tests/` directory contains JSON fixtures for every message type. A conforming client should:

1. Deserialize every S2C fixture successfully
2. Serialize every C2S fixture and produce valid JSON
3. Round-trip: deserialize → re-serialize → compare
4. Handle missing optional fields (e.g., `blocks`, `lang`, `structured` may be absent or null)
5. Handle unknown `type` values gracefully (forward compatibility)
6. Handle unknown `ContentBlock` format values (render fallback)
