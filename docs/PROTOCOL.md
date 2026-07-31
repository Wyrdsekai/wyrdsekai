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
wss://host:port/ws?token=<token>
```

For HTTP endpoints, use either:
- Query parameter: `GET /api/auth/me?token=<token>`
- Header: `Authorization: Bearer <token>`

---

## WebSocket Connection

### Connecting

```
wss://host:port/ws?token=<session-token>
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
  "command": "codeplane.approve",
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
| `format` | string | no | Namespaced type identifier (e.g., `"codeplane.diff"`, `"wyrdsekai.room"`) |
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
6. Destination validates the token (Ed25519 signature verification)
7. Player appears in the destination zone's "docks" room
8. Player receives `room_state` and can interact normally

### Trust Levels

| Level | Duration | Capabilities |
|-------|----------|--------------|
| `tourist` | 1 hour | Read-only visitor, limited interactions |
| `resident` | 24 hours | Can speak, use objects, repeat visits |
| `citizen` | 7 days | Extended access, can own property |

Trust level escalates with repeat visits (tourist → resident → citizen).

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
