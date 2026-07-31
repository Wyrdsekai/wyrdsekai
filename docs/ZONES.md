# Zones and Households

A **household** is one to twenty machines that belong to you: a laptop, a mini
PC, a NAS, a phone. Each runs a Wyrdsekai node. Together they form a mesh —
The Between — and present one continuous world.

A **zone** is a named region of that world. One node is enough to have a zone;
more nodes let a zone span machines, and one household can hold several zones
(`kitchen`, `garage`, `study`).

Nothing here needs a central server. Relays are optional and self-hosted;
federation between households is bilateral and revocable.

## What a zone is

Canonical zone identity is a keypair plus a label —
`zoneId := (householdFingerprint, zoneLabel)`.

`householdFingerprint` derives from the household's Ed25519 public key,
rendered as a W3C DID (`did:wyrd:z6Mk…`) and persisted at `node-identity.json`
on first boot; `wyrd whoami` prints it. `zoneLabel` is a short string you
choose, unique only within your own household. On the wire the canonical form
is `did:wyrd:{fingerprint}:{zoneLabel}`; as a NATS subject token it renders
`{fingerprint}.{label}`.

One key serves three purposes: the discovery/routing peer id, the signer on
every `BetweenEnvelope`, and the NATS credential that scopes what the zone may
publish on a relay. Display name, icon, and tagline are separate manifest
fields — the label is the identifier, not the pretty name.

## Zone naming rules

A zone label must be **1–32 characters** matching
`[a-z0-9]([a-z0-9-]*[a-z0-9])?` — lowercase ASCII alphanumerics with internal
hyphens allowed. No uppercase, no underscores, no dots or colons, no leading or
trailing hyphen. `kitchen`, `bob-studio`, `tea-room-2`, `zone1`, and `a` are
valid; `Kitchen`, `bob_studio`, `-foo`, `foo-`, and `kitchen.main` are not.

**Reserved (case-insensitive): `home`, `self`, `me`, `here`, `origin`.**
`travel home` ends a proxied visit and returns you to your origin zone, or
narrates "you're already home" if you're a local resident — it is never a zone
lookup. Note that `WYRDSEKAI_ZONE_ID` still ships defaulting to `home`, which
the label validator forbids; the spec'd `wyrd zones rename` migration command
is not implemented yet, so pick your label at setup time.

### Short names are local aliases

This is the SSH `known_hosts` model: no registry, and collision is impossible
because aliases never leave their owner's filesystem. Two files under your data
dir hold them — `my-zones` (your own labels, one per line, first is the
default) and `contacts` (other households, by *your* nickname → their DID).

```bash
wyrd zones list | create <label> | remove <label>
wyrd contacts list
wyrd contacts add <alias> <did> [<default-label>]
wyrd contacts rename <old> <new> | update <alias> <new-did> | remove <alias>
```

`wyrd zones create` simply registers a label locally. There is no claiming
ceremony: two households on the same relay can each have a `kitchen`.

### The grammar you type in-world

| Input | Resolves to |
|---|---|
| `travel garage` | `garage` in your `my-zones` |
| `travel alice:kitchen` | `alice` in `contacts` → their fingerprint + label `kitchen` |
| `travel alice` | Alice's default zone |
| `travel did:wyrd:z6Mkp7x…:kitchen` | explicit canonical form (first contact) |
| `travel home` | reserved keyword — never a zone lookup |

Resolution failures carry machine-readable codes (`reserved_keyword`,
`unknown_alias`, `unknown_label`, `malformed_did`, `no_default_zone`,
`ambiguous_label`).

First contact is trust-on-first-use: identity is shared out of band, the
recipient `contact add`s it and verifies the fingerprint before saving. The
first arrival at a new household's zone shows the identity anchor in the room
header; later visits just show `alice:kitchen`.

## The Between — transport

Before the mechanics, the shape of the problem it solves.

A household is not a datacentre and cannot be treated as one. Its machines are
owned by different people, deployed separately, and unreachable most of the time
— a phone on a carrier network with no inbound route, a laptop that closes
mid-sentence, a GPU box that is awake when someone is using it. Any design that
assumes every node can open a socket to every other node excludes phones
entirely, and phones are where most people actually talk to their companion.

So the Between assumes the opposite. **One-way reachability is enough.** No node
needs to be addressable by every other. A node can vanish without taking anything
else down with it, and rejoin without ceremony. Trust is *graded* — joining is an
agreement between parties, not membership in a group — which is what makes
federation between households possible at all rather than merely between machines
that happen to trust each other completely.

This is also why the actor system does not span nodes: cluster membership would
require exactly the mutual reachability a household does not have. See
[ARCHITECTURE.md](ARCHITECTURE.md) for that side of it.

The Between is a NATS mesh carrying Ed25519-signed envelopes. The main server
spawns its own embedded `nats-server` at boot, so a single-node household needs
no extra process and no configuration. In-household subjects follow one
grammar:

```
between.{zoneId}.{sourceNodeId}.{targetNodeId}.{layer}.{topic}
```

Broadcast puts `*` in the target position. Shipping examples: `cluster.hello`,
`cluster.heartbeat` (10s), `cluster.leaving`, `actor.room.{roomId}`,
`probe.ping`/`probe.pong`, `probe.capabilities`, `rooms.announcement`,
`rooms.claim`. A relay leg bridges `between.{zoneId}.>` — a zone only ever sees
its own subtree.

Cross-household traffic rides a separate namespace —
`federation.{zoneId}.gate.*`, `federation.{zone}.tell`,
`federation.inference.{targetZone}.complete` — with a canonical
`federation.{fingerprint}.{label}.gate.*` form subscribed alongside the legacy
one during the current migration.

The relay control plane and the phone tunnel use `wyrd.zone.{zoneId}.…` and
`wyrd.tunnel.{zone}.…`; both are zone-scoped in the relay's NATS ACLs, so one
household's account cannot read another's subtree. `wyrd.discover.zone`,
`wyrd.discovery.capabilities`, and `wyrd.inference.capabilities` are global by
design.

### Ports

`4222` NATS client / zone leg · `4223` NATS WebSocket (mobile) · `8222` NATS
monitoring · `7422` NATS leafnode · `7070` Wyrdsekai HTTP **and** WebSocket
(one port serves both) · `4443` relay public TLS · `9222`/`9280`
relay-internal websocket and registration sidecar.

A relay firewall needs `4443` (or your chosen `RELAY_PORT`) and the zone-leg
port open. That is `4222` by default — but the relay installer **moves itself**
if those ports are already taken, which is what happens when you put a relay on
a machine that is also running a Wyrdsekai zone: the zone wants `4222` too. It
shifts its four backend ports by +100 (so the zone leg becomes `4322`), says so
during install, and joining zones learn the real port from the registration
response rather than assuming. Open whatever it reports, not `4222` by habit.

`WYRD_RELAY_PORT_OFFSET` sets that shift explicitly — useful for a second relay
on one host, and honoured as-is rather than second-guessed. `WYRD_RELAY_INSTANCE`
suffixes the systemd unit names. Both are native-mode features; the docker relay
publishes fixed ports and cannot move.

### How nodes find each other

Three mechanisms: **mDNS** on the LAN (service type `_wyrdsekai._tcp.local.`,
advertising node id, zone id, household id, HTTP port, and NATS URL — browse
with `wyrd discover --lan`), explicitly configured **seed nodes**, and a
**DNS TXT** lookup at `_wyrdsekai.{domain}`. The relay *address* is broadcast;
the relay **token never is** — joining a household always goes through an
explicit consent flow.

Configuration `wyrd setup` writes: `WYRDSEKAI_BETWEEN_ENABLED=true`,
`WYRDSEKAI_NODE_ID=<hostname>`, `WYRDSEKAI_NATS_URL=nats://127.0.0.1:4222`,
`WYRDSEKAI_NATS_AUTO_START`.

## Adding a second machine

Install Wyrdsekai on the new machine as usual (see
[INSTALLATION.md](INSTALLATION.md)), then enroll it.

### The household-key path

```bash
wyrd household key                            # on the hub: print (or mint) the key
wyrd join home-server --household-key <key>   # on the joining node
```

`wyrd household join <host> --household-key <key>` is an alias.

`wyrd join` takes `<host[:port]>`, default port `7070`. It uses the node's real
identity (it refuses to mint a throwaway key), mirrors the hub and every roster
member into the local database, and persists `WYRDSEKAI_NATS_URL`,
`WYRDSEKAI_ZONE_ID`, and `WYRDSEKAI_INFERENCE_HOUSEHOLD_BORROW=true` where the
*service* reads them — `/etc/wyrdsekai/wyrdsekai.conf` on an installed node,
otherwise the data-dir env file. Finish with `wyrd restart`, then check
`wyrd household audit [--limit <n>]` (requires `wyrd login`).

### The pairing-code path

```bash
wyrd federate join --lan                                   # browse LAN, changes nothing
wyrd federate join --request <host> [--name <n>]           # 6-digit code read aloud
wyrd federate join <host> --request --household-key <k>    # pre-shared key, no code
wyrd federate join --relay-url <U> --user <H> --token <T>  # direct config, no pairing
wyrd federate code | household-key [show|generate]         # [admin] on the hub
```

Pairing codes are 6 digits, live 5 minutes, and allow 3 attempts. This path
writes a `[relay]` block into `~/.wyrdsekai/profile.toml`, mode `600`. A device
can be paired with one household at a time.

Or wire it by hand:

```bash
wyrd config set WYRDSEKAI_NATS_URL=nats://192.0.2.10:4222
wyrd config apply && wyrd restart
```

## Relays

A relay is the public meeting point for your household: zones connect **out**
to it, phones connect **in** through it, and cross-household federation rides
it. It is a dumb pipe — NATS routes subjects, Caddy terminates TLS, envelopes
stay Ed25519-signed end to end, so the operator sees subjects and traffic
metadata, not content. Run your own.

### Deploy one

```bash
sh packaging/relay.sh relay.example.com          # docker if present, else native
sh packaging/relay.sh 192.0.2.50:5000            # bare IP, custom port (default 4443)
sh packaging/relay.sh --native relay.example.com # force the no-docker install
```

The host argument is optional and only seeds the dial address baked into join
tokens — the leaf certificate covers every interface IP, and devices pin the
**household CA fingerprint**, not the hostname. Docker mode is a single container built from `deploy/relay/Dockerfile` (NATS +
Caddy + registration sidecar + first-boot cert generation) — there is no
compose file. Native mode installs pinned static `nats-server` and `caddy`
binaries plus a Python venv under `/opt/wyrdsekai-relay` (override with
`WYRD_RELAY_PREFIX`) and `wyrd-relay-{nats,registration,caddy}` systemd units.
Re-running upgrades in place and never rotates identity; `--reset` wipes it and
`uninstall` destroys the household CA along with every pin it issued.

Other flags: `--docker` / `--native`, `--public` / `--private`,
`--mode invite-only|open|commons`, `--owner <did>`, `--bundle-dir DIR`,
`--ssh-tunnel[=jump]`.

### Registration modes

`--mode invite-only` (the default) requires a token for every join and enters
nodes at the HOUSEHOLD tier. `--mode open` is invite-less — the LAN/firewall
perimeter *is* the trust boundary — at HOUSEHOLD-equivalent. `--mode commons`
is invite-less self-serve for a public relay, entering nodes at FLOOR, where a
verified IdentityOutbox plus Web-of-Trust vouches promote them to VOUCHED under
a per-IP rate limit.

`max_registrations` is a hard cap at the join gate (FLOOR 500; VOUCHED and
HOUSEHOLD unlimited). `max_connections` is a detection-grade per-DID ceiling
(FLOOR 2, VOUCHED 5, HOUSEHOLD 20) — the liveness reaper flags overage rather
than severing it, and prunes nodes absent past their tier window (FLOOR 24h,
otherwise 7d).

`--owner <did>` records the admin owner at deploy; without it the deploy mints
a one-time claim token you redeem with `wyrd relay claim <token>`. Get the DID
by running `wyrd whoami` on the administering zone.

> Two documentation caveats. `wyrd whoami` prints a `did:wyrd:…`, but some
> relay docs show `did:key:…` for `--owner` — check against a live `wyrd
> whoami`. And `wyrd relay set-policy`, which appears in several relay
> documents, has **no CLI implementation**; tier policy is currently a relay-side
> signed admin operation only.

### Join a relay from a zone

```bash
sh packaging/relay.sh invite [--ttl SEC]                          # on the relay host
wyrd relay join wyrdjoin://relay.example.com:4443/<code>.<ca_fp>  # on the zone
```

Join codes are 8 characters, single-use, TTL-bound, and per-IP rate-limited.
The joining node **verifies the invite's CA fingerprint against the token**
before it trusts anything; a mismatch aborts hard and saves nothing — that is
the on-path attacker case.

Legacy forms still work (`wyrd relay join <host>[:port] <code>`,
`wyrd relay register 'wyrdrelay://<host>[:<port>]/<token>'`), and stewards can
run `/relay join <token>` in-session. `join`, `register`, and `setup` all accept
the multi-homing flags `--replace` (reset to leg 0 instead of appending),
`--visibility private|public` (default private), and `--allow-public-leg`.

### Operating a relay

```bash
# on the relay host (auto-detects docker vs native)
sh packaging/relay.sh list           # registrations, tiers, liveness, mode, abuse reports
sh packaging/relay.sh claim-mint | remove <pubkey> | uninstall
sudo sh packaging/relay.sh backup [--out path.tgz] | restore <archive.tgz>

# from a zone
wyrd relay status | legs | leave [<url>] | disable | remove
wyrd relay claim <token>
wyrd relay ssh-enable | ssh-disable      # SSH-over-relay into a NAT'd zone
wyrd relay rotate-cert [--ca] | show-cert
wyrd relay peer-invite | peer-accept     # bilateral relay-to-relay peering
```

**Back up the relay identity before any `--reset` or host migration.** The
archive carries the household CA, the invite key, and the registration records;
restoring it onto a new host keeps every existing device pin valid.

### The public relay

We operate one at **`relay.wyrdsekai.org`**, in `--mode commons`, so that
reaching your household from a phone does not first require finding a second
machine with a public address. It is a convenience, not a dependency: nothing
in the design routes through it, and running your own stays the documented
path.

```bash
wyrd relay join relay.wyrdsekai.org      # self-serve; no invite code needed
wyrd relay status
```

You enter at **FLOOR**, the newcomer tier. Standing is per-DID:

| Tier | How you get there | Registrations | Connections/DID | Vouch weight | Pruned after |
|---|---|---|---|---|---|
| `FLOOR` | self-serve join on a commons relay | capped at 500 | 2 | 0.0 | 24h absent |
| `VOUCHED` | verified IdentityOutbox + vouches totalling ≥ 1.0 | unlimited | 5 | 0.6 | 7d absent |
| `HOUSEHOLD` | invited by the operator, or promoted | unlimited | 20 | 1.0 | 7d absent |

A newcomer's vouch is worth nothing (weight 0.0) — that is deliberate, and it
is what stops a flood of fresh DIDs from promoting each other. Two `VOUCHED`
vouchers (0.6 each) or one `HOUSEHOLD` voucher clears the threshold.

Two honest limits. The registration cap is a hard gate — a new entrant is
refused when the tier is full — but `max_connections` is **detection-grade**:
the reaper flags a record as `over_connection_limit` and surfaces it to the
operator, because the sidecar cannot sever a single NATS connection without
dropping the record from auth entirely. Severing stays a deliberate operator
action. And per-tier *bandwidth* limits do not exist: NATS throttling is
account-scoped, not user-scoped, so applying it per tier means splitting into
per-tier accounts with exports and imports for the shared federation subjects.
The registration cap is the flood defence that is actually enforced today.

**What the operator can see.** Envelopes stay Ed25519-signed end to end and
TLS terminates at your device, so a relay operator cannot read what passes
through — including us. What is necessarily visible is metadata: which DID
connected, when, from which address, how much traffic, and which subjects were
routed. If that matters to you, run your own relay; it is three commands, and
the bundle is a download.

## Federation between households

Federation is bilateral and revocable — neither side gets anything until both
have said yes, and either can end it.

```bash
wyrd federate propose <zone>     # on alpha
wyrd federate accept <zone>      # on beta
wyrd federate status [--mesh]
wyrd federate list
wyrd federate revoke <zone>
```

The same verbs exist as `wyrd zone federate|accept|revoke|status <zone-id>`.
`--mesh` renders a both-sides consensus matrix (`agree` / `mismatch` /
`unreachable`) with remediation hints inline — the fastest way to find a
half-open agreement.

`WYRDSEKAI_FEDERATION_AUTO_ACCEPT=true` auto-activates inbound proposals on
receipt. It trusts **any** zone that can reach you: test meshes only.
`WYRDSEKAI_API_URL` overrides the server URL these commands talk to.

Once federated you get cross-zone tells (`tell alpha.someone`), traversal,
capability announcements, and metered cross-zone inference; companion
relocation preserves the soul manifest intact. Metering is currently
**informational only** — usage is recorded, but there is no rejection path yet.

To browse the wider directory (if you have opted into publishing — each zone
self-publishes at `/.well-known/wyrd-zone`):

```bash
wyrd discover [<url> | acct:<handle> | --did <d> | --tag <t>]
wyrd discover --capability <c> | --search "<text>" [--limit N]
```

## Inference across the household

The point of a household mesh is that a CPU-only machine can borrow the GPU in
the other room. Per tick, a node borrows when borrowing is enabled, it has no
local GPU, the peer is a household member, and that peer advertises VRAM > 0.

A borrowed household GPU gets priority 2 — *below* the local CPU backend, so
local CPU remains the health fallback. Peers are rediscovered every 15 seconds,
and the traffic stays inside `between.{household}.>`: this is the household
trust boundary, not federation.

`WYRDSEKAI_INFERENCE_HOUSEHOLD_SHARE` offers this node's accelerator;
`WYRDSEKAI_INFERENCE_HOUSEHOLD_BORROW` uses a peer's. `wyrd setup` sets `SHARE`
true when it detects an accelerator and false on a CPU-only box; `BORROW`
defaults on everywhere, and `wyrd join` forces it on.

The mode switch is `WYRDSEKAI_INFERENCE_MODE`: `local` (default) runs the local
stack; `cloud`/`remote` runs none and requires `WYRDSEKAI_INFERENCE_URL`;
`zone`/`household` runs none and rides the mesh, either pinned via
`WYRDSEKAI_INFERENCE_URL=nats://<zoneId>` or chosen by borrowing.
`WYRDSEKAI_LLAMA_ENABLED=false` suppresses the local stack in any mode.

```bash
wyrd inference status                  # backend, hardware recommendation, share/borrow state
wyrd inference share on|off|status     # the offer half
wyrd inference local [model-path]      # bundled llama-server (CPU or GPU)
wyrd inference remote <url>            # external HTTP endpoint
wyrd inference zone <zoneId>           # delegate to a federated peer zone via NATS
wyrd inference disable
```

`wyrd inference zone` requires an active bilateral federation agreement.
Cross-zone requests are pinned to a named local backend on arrival, so a
request from zone A to zone B cannot be bounced onward to zone C; the default
timeout is 120 seconds.

## Health of the mesh

`wyrd status`, `wyrd doctor`, and `wyrd state dump --summary` cover one node.
`wyrd version --mesh` fans version and build-hash queries out to every
federated peer and flags schema mismatches and build drift — the fastest way to
spot a node that missed an upgrade.
