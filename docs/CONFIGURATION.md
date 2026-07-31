# Configuration — models, backends, and keys

Wyrdsekai has one configuration file and a CLI that edits it. On a packaged
install the file is `/etc/wyrdsekai/wyrdsekai.conf` — that is the *only* file the
service reads, and `wyrd config set` writes it. From source it is
`<data-dir>/env`. Every setting below is an environment variable, so anything
that can set an environment variable can configure a node.

Editing a different file will not take effect. On an installed node the service
is started by systemd (or launchd), and its unit deliberately loads one
`EnvironmentFile` and no other — a second config that the service silently
ignored is exactly the kind of split that wastes an afternoon. Prefer
`wyrd config set`, which always targets the right file.

```bash
wyrd config set WYRDSEKAI_ZONE_ID=hearth     # write a setting
wyrd config                                  # show current settings
```

Defaults live in `core/src/main/resources/reference.conf`. Anything there can be
overridden by the matching `WYRDSEKAI_*` variable — the conf file names them
inline, so that file is the authoritative list when this document and the code
disagree.

---

## Where inference runs

The single most important choice. `WYRDSEKAI_INFERENCE_MODE` takes three values:

| Mode | Meaning |
|---|---|
| `local` | A model runs on this machine. The default, and the point of the project. |
| `cloud` | Calls go to an API. Set `WYRDSEKAI_INFERENCE_URL`. |
| `zone` | Borrow another household node's inference over the Between. |

Anything unrecognised is treated as `local` with a warning rather than a crash.

```bash
wyrd inference remote https://api.example.com/v1   # switch to cloud
wyrd inference                                     # what is serving right now
```

### Local models

`wyrd setup` downloads and pins a companion model; `WYRDSEKAI_MODEL_PATH` points
at it. The default pairing ships **a 9B drive model and a 4B voice model** — see
[MODELS.md](MODELS.md) for why two, why these sizes, and how a phone runs the
small one locally while borrowing the large one from the household.

Useful neighbours:

- `WYRDSEKAI_INFERENCE_TIMEOUT` (default `300`) — seconds
- `WYRDSEKAI_INFERENCE_CONCURRENCY` (default `1`) — parallel generations
- `WYRDSEKAI_LLAMA_URL`, `WYRDSEKAI_VOICE_URL` — where the runtimes listen

### Sharing inference across a household

A GPU box can serve the rest of the house:

- `WYRDSEKAI_INFERENCE_HOUSEHOLD_SHARE` — offer this node's inference to peers.
  Turning it on also binds NATS on all interfaces, because a peer that cannot
  reach you cannot borrow from you.
- `WYRDSEKAI_INFERENCE_HOUSEHOLD_BORROW` (default `true`) — use a peer's
  inference when this node has none.

That pair is what lets a laptop with no GPU run a companion that thinks on the
desktop upstairs.

---

## Cloud API keys

Keys are **not** environment variables in the normal case. They live in **The
Safe** (`core/…/room/TheSafe.java`), a topology-gated secret keeper: an agent can
*use* a credential without being able to *read* it, so a key cannot be
exfiltrated through the agent's own context window.

The same rule governs MCP credentials, which are held in `McpKeyStore` — see
[MCP.md](MCP.md).

If you set a provider key directly in the environment for a quick trial,
understand that you have opted out of that protection for the duration.

---

## Zone identity and the Between

| Variable | Meaning |
|---|---|
| `WYRDSEKAI_NODE_NAME` | This machine's name within the zone |
| `WYRDSEKAI_ZONE_ID` | The zone this node belongs to |
| `WYRDSEKAI_ZONE_PUBLIC_URL` | How outsiders reach this zone |
| `WYRDSEKAI_BETWEEN_ENABLED` | Federation on/off |
| `WYRDSEKAI_NATS_URL` | Message bus (defaults to the embedded server) |
| `WYRDSEKAI_NATS_AUTO_START` | Whether the server spawns its own NATS |

On a packaged install `WYRDSEKAI_NATS_AUTO_START=false` and a systemd unit owns
NATS; from source the server starts its own. Both are correct for their context —
see [ZONES.md](ZONES.md).

Relay settings (`WYRDSEKAI_RELAY_URL`, `_USER`, `_TOKEN`) and the SSH-tunnel
group (`WYRDSEKAI_SSH_TUNNEL_*`) configure how a household is reachable from
outside without opening a port. [ZONES.md](ZONES.md) covers the topology.

---

## Budgets

Real money and real compute both have ceilings:

- `WYRDSEKAI_MCP_DAILY_SPEND_CAP` (default `10.0`) — hard daily cap on paid MCP
  tool calls, enforced at the gateway rather than advised
- The `wyrdsekai.familiar` and `wyrdsekai.bunshin` blocks in `reference.conf`
  bound how many sub-agents can exist and how large they may get

---

## Coding backends

Which coding agent a companion can summon — goose, Codex, OpenCode, OpenHands,
Aider, Gemini CLI, Claude SDK, CodePlane, Devin — is configured through the
`wyrdsekai.coding` block, one `WYRDSEKAI_CODING_<BACKEND>_*` group each, plus
`WYRDSEKAI_CODING_DEFAULT_BACKEND` and an egress gate. That surface is large
enough to deserve its own document: see [EXTENDING.md](EXTENDING.md).
