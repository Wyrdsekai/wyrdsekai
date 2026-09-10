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
- `WYRDSEKAI_INFERENCE_SIBLING_WAIT_SECONDS` (default `120`) — when `WYRDSEKAI_MODEL_PATH` names
  a model and a bundled server is expected on :8200/:8201 but not serving yet (they take ~30 s
  after boot), how long to wait for it before starting a local `llama-server` of our own
- `WYRDSEKAI_LLAMA_URL`, `WYRDSEKAI_VOICE_URL` — where the runtimes listen

### Sparse (Mixture-of-Experts) models

Sparse-active models — a large total parameter count of which only a small
expert subset fires per token (`32B-A9B`, `8B-A1B`, …) — run well on modest
GPUs **if the placement is right**: attention and KV cache on the accelerator,
expert tensors in system RAM. Three knobs select that split; each is a no-op
for dense models:

- `LLAMA_CPU_MOE` (Docker) / `WYRDSEKAI_LLAMA_CPU_MOE` (native) —
  `all` (or `on`) keeps **all** expert tensors in system RAM (`--cpu-moe`);
  a number `N` keeps the experts of the first `N` layers in RAM
  (`--n-cpu-moe N`); unset or `0` means dense placement.
- `LLAMA_CHAT_TEMPLATE_KWARGS` / `WYRDSEKAI_LLAMA_CHAT_TEMPLATE_KWARGS` —
  JSON object passed to the chat template (`--chat-template-kwargs`), for
  models whose templates take switches such as `{"enable_thinking":false}`.
- `LLAMA_CACHE_RAM` / `WYRDSEKAI_LLAMA_CACHE_RAM` — MiB of system RAM each
  `llama-server` may use for its prompt cache (`--cache-ram`; default `1024`,
  `0` disables). llama-server's own default is 8 GiB **per server**, which on a
  12–16 GB box with a drive and a voice server ends in the OOM killer.
- `LLAMA_SKILLS_EXTRA_ARGS` / `WYRDSEKAI_LLAMA_EXTRA_ARGS` — extra
  `llama-server` flags, word-split verbatim. The escape hatch.

These apply to the drive/skills server. The voice server stays a small dense
model by design.

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

## Embeddings

Retrieval (the library, the Study, memory recall) uses dense vectors beside BM25. The
embedding model runs in one of two places:

- **In-process** (the default): an ONNX session inside the server. Fine for query-time
  work; far too slow for indexing a library — bge-m3 embeds about two chunks per second on
  a CPU, and it does not scale with threads.
- **Served** (`WYRDSEKAI_EMBEDDING_URL`): an embedding server on the GPU, OpenAI-style
  `/v1/embeddings`, port 8202, started by `wyrd start` and reported by `wyrd status`. Two
  servers speak that route and serve the same bge-m3, chosen with `WYRDSEKAI_EMBED_SERVER`:
  - `llama` (the default on existing nodes): llama.cpp `llama-server --embedding` on the
    bge-m3 GGUF `wyrd setup` fetches, as `wyrdsekai-llama-embed`. Give
    `WYRDSEKAI_EMBEDDING_URL` a comma-separated list to run replicas — one server is
    single-threaded on its CPU side, so two or three processes on the same card scale nearly
    linearly.
  - `tei`: Hugging Face's Text Embeddings Inference, as `wyrdsekai-tei-embed`. It keeps the
    whole batch on the card and embeds several times faster than llama-server for the same
    model (measured: ~13 chunks/s against ~115 on one consumer card), which is the difference
    between a night and an hour for a library. `wyrd start` picks the image for the card's
    compute capability (Turing, Ampere, Ada, Hopper and Blackwell are covered; without an
    NVIDIA card it runs the CPU image, which is slow) and fetches the model from Hugging Face
    into `data/models/tei` on first start. `wyrd setup` offers it on NVIDIA tiers; switch a
    running node with `wyrd config set WYRDSEKAI_EMBED_SERVER=tei` and `wyrd restart`. Same
    model, same stamp, so its vectors and llama-embed's are interchangeable — no re-index.

Index-time and query-time embeddings must come from the same embedder, so a served model is
stamped with its own version and existing vectors are treated as stale, exactly as for a
model change. With a server present, new ingests embed as they are written
(`WYRDSEKAI_EMBED_AT_INGEST` forces this on or off); without one they stay text-only.

| key | meaning | default |
|---|---|---|
| `WYRDSEKAI_EMBEDDING_MODEL` | model id (`bge-m3`, the MiniLM default, …) | registry default |
| `WYRDSEKAI_EMBEDDING_URL` | embedding server(s), comma-separated | unset (in-process) |
| `WYRDSEKAI_EMBED_SERVER` | which server `wyrd start` runs on 8202: `llama` or `tei` | `llama` |
| `WYRDSEKAI_EMBED_AT_INGEST` | embed chunks as they are indexed | on when served |

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

## Updates

The node knows what release it runs (the `VERSION` file the installer ships beside the
program) and asks GitHub once a day what the latest is. `wyrd update` says both;
`wyrd status` and `wyrd doctor` say when a newer one exists, and `doctor` says the same for
the two programs a household leans on, CodeZaiku and ResearchZosho.

```
wyrd update                 # installed, latest, mode
wyrd update now [VERSION]   # download this platform's installer from the GitHub release,
                            # verify it against the release's SHA256SUMS, install it
                            # (the package's own upgrade: databases snapshotted, service restarted)
wyrd update auto on|off     # let the node do that itself
wyrd coding update codezaiku   # the coder's latest release, verified against its sums
wyrd researcher update         # the librarian's own updater
```

With `WYRDSEKAI_UPDATE=auto` the node installs a newer release at a quiet moment inside its
window — nothing in flight for ten minutes, between `WYRDSEKAI_UPDATE_WINDOW` (default
`03:00-05:00` local, the housekeeping hour) — and restarts; one attempt per version per day,
never on a dev build, never past `WYRDSEKAI_UPDATE_PIN`. On Windows the installer needs an
elevation prompt a service cannot show, so auto mode downloads and verifies the `.msi` and
`wyrd update now` finishes it. The mesh update protocol (`WYRDSEKAI_UPDATE_CHANNEL`, a signed
release channel for a fleet) is separate and unchanged.

| key | meaning | default |
|---|---|---|
| `WYRDSEKAI_UPDATE` | `check`: say when a newer release exists; `auto`: install it; `off`: ask nothing of GitHub | `check` |
| `WYRDSEKAI_UPDATE_INTERVAL` | how often the node checks | `6h` |
| `WYRDSEKAI_UPDATE_WINDOW` | when auto mode may install, `HH:MM-HH:MM` local | `03:00-05:00` |
| `WYRDSEKAI_UPDATE_PIN` | stay on this version | unset |

## Budgets

Real money and real compute both have ceilings:

- `WYRDSEKAI_MCP_DAILY_SPEND_CAP` (default `10.0`) — hard daily cap on paid MCP
  tool calls, enforced at the gateway rather than advised
- The `wyrdsekai.familiar` and `wyrdsekai.bunshin` blocks in `reference.conf`
  bound how many sub-agents can exist and how large they may get

---

## The research librarian

A household can be a patron of [ResearchZosho](https://researchzosho.org), the research
librarian: the companions ask it at the librarian's desk, consult what it has established
before they search, hand it questions for the night, and re-check what they cited at sleep.
`wyrd researcher setup` installs it (its own checked one-line installer), runs its setup, and
connects this node; `wyrd researcher link` connects to one already running. Either writes:

- `WYRDSEKAI_LIBRARY_SERVICE=researchzosho` — the registered MCP service that plays the
  `library` role. Items name the role, never the product.
- `WYRDSEKAI_MCP_KEY_RESEARCHZOSHO` — the bearer token the librarian issued for this
  household's identity (`researchzosho patron token <did>`); read by the key store as the
  service's `safe_key`. Absent for a librarian reached over stdio.
- the service entry in `<data dir>/mcp-services.json` (see [MCP.md](MCP.md)).
- `WYRDSEKAI_LIBRARY_WEBHOOK_SECRET_RESEARCHZOSHO` — the secret the librarian signs its
  pushed changes with, when `link` subscribed this node (`--no-webhook` skips it).
- `<data dir>/library-readers.json` — who may read the household's own library door over
  HTTP, with token hashes (`wyrd library reader add|list|remove`).
- `WYRDSEKAI_LAN_IP` — the address this node tells the librarian to push to, when the one
  it finds on its own is wrong.

`WYRDSEKAI_MCP_STRICT_GRANTS=true` additionally requires a grant per companion for the
service, given from the steward's Study.

## Coding backends

Which coding agent a companion can summon — CodeZaiku (the bundled default),
goose (the recommended alternative if you'd rather not use CodeZaiku), pi,
Codex, OpenCode, OpenHands, Aider, Gemini CLI, Claude SDK, Devin —
is configured through the `wyrdsekai.coding` block, one
`WYRDSEKAI_CODING_<BACKEND>_*` group each, plus
`WYRDSEKAI_CODING_DEFAULT_BACKEND` and an egress gate. That surface is large
enough to deserve its own document: see [EXTENDING.md](EXTENDING.md).

Three keys worth knowing here because they answer real topologies:

- `WYRDSEKAI_INFERENCE_URL` — this node's drive lives on ANOTHER machine.
  Every keyless backend follows it; nothing else needs configuring.
- `WYRDSEKAI_CODING_OPENHANDS_AGENT_SERVER_URL` — where the OpenHands V1
  agent-server listens (default `http://localhost:8000`). The old
  `_MCP_URL` name is still read for compatibility but names a protocol
  OpenHands no longer speaks.
- Drive sizing is part of the contract, not a knob: CodeZaiku's loop needs a
  **12K-token context minimum (16K comfortable)**; OpenHands wants **32K**.
  An 8K drive passes health checks and still fails real dispatches.
