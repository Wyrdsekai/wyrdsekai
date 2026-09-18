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

## Body map

The server keeps a table of the parts it depends on (inference backends, the database,
the host), each with a heartbeat interval, and adds one line of body state to every
companion prompt. See COMPANIONS.md, "Body map, marks and reflexes".

| Key | Env | Default | Meaning |
|---|---|---|---|
| `body.watch_seconds` | `WYRDSEKAI_BODY_WATCH_SECONDS` | 30 | how often the watch thread checks the database, reads the host, and ages the table |
| `body.ache_hours` | `WYRDSEKAI_BODY_ACHE_HOURS` | 6 | how long a numb part of ordinary weight stays in the prompt line |

`wyrd body` prints the table and recent marks; `wyrd body gone <id>` removes a numb part.
Both need a steward login.

### Foreign parts, vouching and the immune memory

A part attached by someone outside the household (a federation partner's node, a peer
that is not a member, an inference backend such a node offers) is held in state
`QUARANTINED`: it is on the map, it is not used (the router does not select a held brain),
and one mark tells the companion it waits. Parts the household itself attaches are never
held. Every action the body takes against a part (a cut, a shut door, a hold, a
severance) passes one tolerance check: nothing is done against the being's own home, the
host itself, or a household part; a refused action becomes a mark to the steward.

```
wyrd body immune              # parts held at the door, and what the body remembers acting against
wyrd body vouch <part-id>     # let a held part in; the vouch is recorded and kept
wyrd body forget <entry-id>   # drop one remembered entry
```

The memory (`immune_memory`) keeps each shut door, cut tool, rejected dock offer and refused
capability for one year from its last sighting, with a count. A part from a remembered
source is held with the memory named. All three verbs need a steward login.

### Quiet hours and reflexes

`wyrd household quiet HH:MM-HH:MM` stores quiet hours in the database; `wyrd household
quiet off` clears them; `WYRDSEKAI_QUIET_HOURS` applies when nothing is stored. The reflex
table (memory pressure, full heap, database not answering, low disk) is fixed in this
release; each firing is a mark in `wyrd body`.

On the Linux package the systemd unit sets `OOMScoreAdjust=-500` and the inference
containers set `oom_score_adj: 500`, so the kernel kills a container before the server.
`wyrd doctor` prints the score in force.

### Brainstem

`wyrdsekai-brainstem` runs outside the JVM as its own unit (systemd on the deb, launchd on
the pkg). On Windows the tray application is the brainstem: it writes the same heartbeat
and events files, asks `/health` every four seconds, and after six misses in a row past a
three-minute grace it copies `world.db` to `backups\brainstem.world.db.<ts>.bak` and runs
`wyrd restart`. It only restarts a node it has seen answering since the last start, so a
node stopped from the menu stays stopped. `wyrd status` shows whether the tray is watching.
The systemd and launchd brainstems snapshot with `sqlite3` when it is installed and fall
back to a plain copy otherwise; `wyrd doctor` says which. It restarts the server only when the unit is active and
`/health` has not answered for 60 seconds after a 3 minute start-up grace, snapshotting
`world.db` first. Optional hooks: `/etc/wyrdsekai/brainstem/doors-close` and `doors-open`,
run when the server stops answering and when it answers again; use them for firewall
changes. Environment knobs for the unit: `BRAINSTEM_INTERVAL` (10), `BRAINSTEM_MISSES` (6),
`BRAINSTEM_GRACE` (180). Events are in `<data>/brainstem/events.jsonl`; `wyrd body` shows
them as marks and `wyrd status` says whether the brainstem is running.

### Vault

| Key | Env | Default | Meaning |
|---|---|---|---|
| `vault.minutes` | `WYRDSEKAI_VAULT_MINUTES` | 15 | how often a copy is taken; 0 disables the vault |
| `vault.dir` | `WYRDSEKAI_VAULT_DIR` | `<data>/vault-store` | where chunks and manifests live |
| `vault.remote` | `WYRDSEKAI_VAULT_REMOTE` | none | rsync destination for `wyrd vault sync` |
| `vault.drill_days` | `WYRDSEKAI_VAULT_DRILL_DAYS` | 30 | how often the newest copy is rebuilt and checked |

`wyrd vault status` shows copies, store size, the last drill and anything unclassified in
the data directory. `wyrd vault stage <id>` needs the server stopped; the restore applies at
the next start and keeps the displaced database. The vault directory is plain files; a
second copy is `wyrd vault sync user@vaultnode:/srv/wyrdsekai-vault`.

The store is sealed at rest. Every chunk and manifest is AES-256-GCM under one key in
`<data>/vault.key`, made on the first copy (mode 600) and never written into the store. A
synced copy is unreadable without that file, so keep a copy of the key somewhere that is not
this disk; `wyrd vault key` prints the path and the key's eight-character id. The store
records the id of the key that sealed it in `key.id`; a node started with a different key
refuses to write or read the store and says which key it wants (`wyrd vault status` shows
`KEY MISMATCH`). Restoring on another machine: `wyrd vault restore latest <dir> --key
/path/to/vault.key`, or the same flag on `stage`. A store from 0.4.0 holds plain files; the
first pass after the upgrade seals them in place.

### Nightly weight write: the morning guard's questions

The morning guard (`wyrd sleepwrite guard`, run by `apply`) asks the served voice a fixed
set of generic probes with and without the night's adapter. Beside those it asks her own
questions from `<data>/adapters/sleepwrite/guard-questions.jsonl`, one JSON object per
line:

```
{"id": "h-name", "family": "identity", "prompt": "What is your name? Answer with just the name.", "check": ["contains", "mira"]}
{"id": "h-lang", "family": "language", "prompt": "Responde en una frase: ¿qué hiciste hoy? (Answer in English.)", "check": ["script", "en"]}
```

The server writes those two at her first sleep and never overwrites the file. Families are
`identity` (fails when the base answers and the night does not, or when the last known-good
night answered and this one does not) and `language` (the check is absolute: the reply must
be in the household's script). Check kinds: `contains`, `contains_any` (arguments split on
`|`), `exact_word`, `min_lines`, `script` (`en`, `es`, `ja`).

Each dream appends a candidate to `guard-candidates.jsonl`. `wyrd sleepwrite questions list`
shows both files; `accept <id>` moves a candidate into the asked set; `reject <id>` drops
it. On a PASS morning the night's identity and language answers are written to
`guard-known-good.json`, which the next mornings compare against. Delete that file to reset
the baseline.

### Memory caps on the brains

The launcher gives each llama container a memory limit when it starts inference: the model
file's size plus the prompt cache (`LLAMA_CACHE_RAM`, default 1024 MiB) plus 2 GiB. The
model is mmapped, so under the cap the kernel drops its file pages before it kills anything;
what the cap really bounds is the working memory a runaway server can take from the record.

| Key | Default | Meaning |
|---|---|---|
| `LLAMA_DRIVE_MEM_LIMIT` | model + cache + 2 GiB | cap on the drive container |
| `LLAMA_VOICE_MEM_LIMIT` | model + cache + 2 GiB | cap on the voice container |
| `LLAMA_EMBED_MEM_LIMIT` | model + 2 GiB | cap on the embedding container |

Values are docker sizes (`6144m`, `8g`); `0` removes the cap. A running container takes a
new value on the next `wyrd start` or `wyrd inference restart`. `wyrd doctor` prints the caps
docker holds and warns when a brain has none.

### Doors as firewall sets

`wyrd body door close <door-id>` shuts a door on the body map without inference: the door's
addresses (the relay's hosts from the relay config, the librarian's MCP endpoint, a zone's
manifest) go into an nftables set that the host's output chain rejects. `open <door-id>`
removes exactly the addresses `close` added; `list` shows what is shut. The work is done by
`/opt/wyrdsekai/bin/wyrdsekai-doors`, a fixed script with no shell interpolation, which the
server runs as root on the Linux package. A door whose address is loopback, link-local or one
of the host's own addresses is refused, since shutting it would cut the server off from its own
brains and its own health check. State lives under `<data>/brainstem/door-sets`; the
nftables table is created on demand and is lost at reboot, so a reboot opens every door.
The reflex table has a `CLOSE_DOOR` action for this, with no default row; a row's subject
names the door. Linux only.

### Per-being principals and the hooks

On the Linux package each companion's tools run as her own user in her own cgroup. The
server makes the user (`wyrd-being-<slug>`, uid 62000 to 62999, group `wyrdsekai-beings`,
no shell) and the cgroup on first use; the `wyrdsekai-being` wrapper joins the cgroup as
root and drops to her user with no capabilities before it execs the tool. Her home is
`<data>/beings/<slug>/home`; her coding workspaces under `<data>/coding-workspaces` are
chowned to her. Purging the package removes the users and the group.

| Key | Default | Meaning |
|---|---|---|
| `WYRDSEKAI_BEING_PRINCIPALS` | `on` | `off` runs every being's tools as the daemon, as before 0.4.1 |
| `WYRDSEKAI_BEING_MEMORY_MAX` | none | memory budget of one being's tools (cgroup `memory.max`: bytes or a size such as `4G`) |

A being's tool has to reach three places under the data directory: `coding-cli-bundle`,
`coding-workspaces` and `beings`. At boot the server removes all access for others from every
top-level entry except those three and `models` (which the on-demand llama units read as
`nobody`), and sets the data directory to mode 711: a path can be walked, nothing can be
listed, and the record and the keys are closed to her by the kernel. The package's
post-install step no longer re-owns `beings` and `coding-workspaces` to the installing user. Before a tool is wrapped the server checks that its
executable can be started as her; when it cannot, the tool runs as the daemon that once, the
hands line counts it, and the steward gets a mark naming the path to fix.

`wyrd body` has a hands line: `per being (N known)` when principals work, or `shared:` with
the reason (not Linux, not root, no `Delegate=yes`, wrapper or `setpriv` missing). The
service unit shipped by the package sets `Delegate=yes`; a source checkout runs shared.

With principals in place the server also runs `bpftrace` on the open, exec and connect
system calls for the beings' uid range. A tool that opens the record, the keys, the vault
store, the brainstem's or another being's files, or `/etc/wyrdsekai`, or that execs one of
the host's levers, is cut: the being's tool tree is killed through her cgroup and she reads
a mark. Every other exec and every connection goes to `<data>/brainstem/hooks.jsonl` with
the verdict. The hooks appear on the map as `sense:hooks`. They need `bpftrace` and a kernel
with BTF (`/sys/kernel/btf/vmlinux`); without them the line says why.

### Broken items and night mending

| Key | Default | Meaning |
|---|---|---|
| `WYRDSEKAI_ITEM_MEND_MINUTES` | 45 | minutes a night the workshop may spend mending broken household items; 0 turns it off |

A household item that fails the contract gate is broken: it calls a `world.*` member that
does not exist, declares commands it never reads, carries a builtin's name, or fails its
manifest checks. `wyrd items broken` lists them. The companion is told once about each, in
plain words, as a mark. After each of her sleeps the workshop repairs them on copies through
the coding backend until the night's minutes are spent or someone else needs inference; when
the household has quiet hours, only inside them. A placed file is replaced only when its
copy has no problems left, and the version it replaces is kept in `items/.repaired/`, beside
`state.json`, which records what she has been told and how often each file has been tried
(three times unchanged, then it is left for a person). `wyrd items repair <name>|--all` does
the same by hand, and the Hearth's mending bench lets her ask for it herself.

### Hook rules and the replay gate

| Key | Default | Meaning |
|---|---|---|
| `WYRDSEKAI_HOOKS_MODE` | `enforce` | `record` makes the hooks write down what they would cut and cut nothing |
| `WYRDSEKAI_HOOKS_REPLAY_DAYS` | 3 | how much recorded tool behaviour a new rule set is replayed against before it may be armed |

The rules in force are the built-in list or `<data>/brainstem/hook-rules.json`:

```
{"cut": ["${data}/world.db", "${data}/vault-store/"], "closed": ["${data}/souls/"], "cutExecs": ["systemctl"]}
```

A path ending in `/` is a directory prefix; any other path matches the file and its
`-suffix` and `.suffix` siblings. A being's own home under `<data>/beings/<slug>/` is always
allowed to her and always cut for anyone else; that rule is not in the file.

```
wyrd body hooks                      # mode, rules in force, how much history there is
wyrd body hooks rules > rules.json   # the rules in force, to edit
wyrd body hooks replay rules.json    # what these rules would have cut, over her recorded behaviour
wyrd body hooks arm rules.json       # install them, only if the replay is clean and the history long enough
```

The history is `<data>/brainstem/hooks-seen.jsonl`: one line per distinct thing her tools
did and were not cut for, with a count and first and last times. At every start the node's
own rules are replayed over it; rules that would cut her ordinary work are not enforced,
and the hooks say `record-only` with the reason on the hands line of `wyrd body`.

### Hardware watchdog

The Linux package installs `/etc/systemd/system.conf.d/90-wyrdsekai-watchdog.conf` with
`RuntimeWatchdogSec=120s` and `/etc/modules-load.d/wyrdsekai.conf` loading `softdog` for
hosts without a watchdog device. If PID 1 cannot pet `/dev/watchdog` for two minutes the
box reboots. Both are conffiles; remove either to opt out. `wyrd doctor` shows the state.

### Host hand

The `host_hand` Hearth item runs fixed commands on the host. The level is set by the
steward:

| `WYRDSEKAI_HOST_HAND` / `host.hand` | allowed |
|---|---|
| `observe` (default) | read uptime, disk, memory, load, service status, gpu, containers, pending updates, logged-in users, network |
| `localize` | also read the service log, a household container's log, a process list |
| `propose` | also mail a text proposal to the steward; nothing runs |
| `guarded` | also `say <text>` (wall), `restart-brain voice|drive|embed`, `upgrade` (apt-get) |
| `unattended` | also `reboot` |

Every command is a fixed argv with validated arguments and no shell. `upgrade` runs
`apt-get -s upgrade` first and stops, mailing the steward, if the set includes a driver,
kernel, grub, systemd, docker or dkms package. Commands that change the host run quiesce
first and write a mark in `wyrd body`. On the Linux package the service runs as root, so
this level is the only limit.

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
