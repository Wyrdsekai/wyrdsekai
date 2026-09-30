# Zones and Households

Wyrdsekai can run on more than one computer. This page explains how to add a
second machine to your household, and how to connect your household to
someone else's.

You do not need any of this to start. One computer is a complete household.
Come back here when you want to:

- add another machine, for example a laptop that borrows the graphics card in
  your desktop,
- visit or talk to a friend's household,
- understand how your machines find each other.

To reach your companion from your phone while you are away, see
[RELAY.md](RELAY.md).

## The words used here

- A **node** is one computer running Wyrdsekai.
- A **household** is the set of nodes that belong to you, from one to twenty:
  a laptop, a mini PC, a NAS, a phone. Together they present one continuous
  world.
- A **zone** is a named part of that world, such as `kitchen`, `garage` or
  `study`. One node is enough to have a zone. More nodes let a zone span
  machines, and one household can hold several zones.
- **The Between** is the network that links your nodes. It runs on NATS, a
  small messaging program. Wyrdsekai starts its own copy when it boots, so a
  one-machine household needs no extra program and no setup.
- A **relay** is an always-on machine on the internet that your nodes and your
  phone can all reach. Relays are optional and you can run your own. See
  [RELAY.md](RELAY.md).
- **Federation** is an agreement between two households that lets their zones
  talk to each other. Neither side gets anything until both have said yes, and
  either side can end it.

Nothing here needs a central server.

## Add a second machine

First install Wyrdsekai on the new machine as usual. See
[INSTALLATION.md](INSTALLATION.md). Then join it to your household in one of
three ways.

### With the household key

The household key is a shared key that your first machine, the hub, gives to
new machines. Print it on the hub. It is created the first time you ask.

```bash
wyrd household key                            # on the hub: print (or mint) the key
wyrd join home-server --household-key <key>   # on the joining node
```

Replace `home-server` with the hub's name or address. `wyrd join` takes
`<host[:port]>`, and the port defaults to `7443`, the hub's encrypted port.
`wyrd household join <host> --household-key <key>` does the same thing.

Copy the whole line `wyrd household key` prints. It is two parts joined by a
dot: the key, then the fingerprint of the hub's certificate. The new machine
checks that the machine it reached has that certificate before it sends the
key. If it does not match, nothing is sent. A key without the fingerprint is
refused. Hubs from before 0.5.0 must be updated first.

`wyrd join` uses the new node's real identity. It refuses to make a throwaway
key. The new node also proves it holds that key: it signs a one-time challenge
from the hub, and the hub refuses to enrol a key without that proof. It copies
the hub and every other household member into the new node's database. The hub
gives the new machine its own login for the household bus, and the hub's
certificate; the machine keeps both in `nats/hub.json` in its data folder
(readable by the node's user only) and uses them for every connection to the
hub, always encrypted. It saves `WYRDSEKAI_NATS_URL`, `WYRDSEKAI_ZONE_ID` and
`WYRDSEKAI_INFERENCE_HOUSEHOLD_BORROW=true` where the service reads them. On an
installed node that is `/etc/wyrdsekai/wyrdsekai.conf`. Otherwise it is the env
file in the data directory.

A machine that joined before 0.5.0 has no login. After the hub is updated, run
`wyrd join` on that machine again, then `wyrd restart`.

Finish with `wyrd restart`. Then check the household's record of what happened
with `wyrd household audit [--limit <n>]`. That needs `wyrd login` first.

### With a pairing code

A pairing code is a 6-digit number the hub gives you, which you read aloud or
type on the new machine. It lasts 5 minutes and allows 3 attempts.

```bash
wyrd federate join --lan                                   # browse LAN, changes nothing
wyrd federate join --request <host> [--name <n>]           # 6-digit code read aloud
wyrd federate join --request <host> --household-key <k>    # pre-shared key, no code
wyrd federate join --relay-url <U> --user <H> --token <T>  # direct config, no pairing
wyrd federate code | household-key [show|generate]         # [admin] on the hub
```

These talk to the hub over HTTPS on port `7443`. With a household key from
`wyrd household key`, the hub's certificate fingerprint rides in the key. With
a pairing code, the new machine shows the fingerprint of the certificate it
reached; `wyrd federate code` on the hub shows the same line. Compare them
before you type the code. You can also pass it with `--ca-fp <fingerprint>`.
On Windows the fingerprint is required.

This path writes a `[relay]` block into `~/.wyrdsekai/profile.toml`. The file
gets mode `600`, so only you can read it. A device can be paired with one
household at a time.

### By hand

Since 0.5.0 the hub's bus (port `4222`) is encrypted and every machine needs
its own login, which only `wyrd join` hands out. Pointing a machine at the hub
by hand, with `WYRDSEKAI_NATS_URL=nats://<hub>:4222` alone, no longer connects.

While you update a household, the hub can let old machines in as before:
set `WYRDSEKAI_NATS_LAN_PLAINTEXT=true` on the hub and restart it. Then anyone
on your network can use the bus without a login, read its traffic and act as
any member, as before 0.5.0. `wyrd doctor` warns while it is on. Re-join each
machine with `wyrd join` and remove the setting.

A node joined this way is not on the household roster. The other machines
still hear it, but they do not take account changes from it: accounts, roles,
removals and settings replicate only between enrolled machines. Use
`wyrd join` for a full member.

## Name your zones

A zone's **label** is its short name. It must be 1 to 32 characters: lowercase
letters, digits, and hyphens in the middle. The exact pattern is
`[a-z0-9]([a-z0-9-]*[a-z0-9])?`. No capitals, no underscores, no dots or
colons, and no hyphen at the start or end.

- Valid: `kitchen`, `bob-studio`, `tea-room-2`, `zone1`, `a`.
- Not valid: `Kitchen`, `bob_studio`, `-foo`, `foo-`, `kitchen.main`.

**Five names are reserved, in any mix of capitals: `home`, `self`, `me`,
`here`, `origin`.** `travel home` always means "take me back". It ends a visit
to another zone and returns you to the zone you came from. If you live in the
zone you are in, it tells you you're already home. It never looks up a zone
called `home`.

Pick your label when you set up. Wyrdsekai still ships with
`WYRDSEKAI_ZONE_ID` set to `home` by default, which the label rules forbid. A
`wyrd zones rename` command to move to a new label is planned but not built
yet.

### Short names are your own

Short names work like SSH's `known_hosts` file. There is no central registry.
Your short names never leave your machine, so they cannot clash with anyone
else's. Two files in your data directory hold them:

- `my-zones` lists your own labels, one per line. The first is your default.
- `contacts` lists other households, under a nickname *you* choose, each
  pointing at that household's identity.

```bash
wyrd zones list | create <label> | remove <label>
wyrd contacts list
wyrd contacts add <alias> <did> [<default-label>]
wyrd contacts rename <old> <new> | update <alias> <new-did> | remove <alias>
```

A household's identity is written as a DID, a string that starts with
`did:wyrd:`. `wyrd whoami` prints yours.

`wyrd zones create` only records a label on your machine. A relay is
different: there, a zone name belongs to the first household that registers
it. If another household on the same relay already uses `kitchen`, joining
that relay with `kitchen` is refused, and you pick another name
(`WYRDSEKAI_ZONE_ID`). A second machine of your own household can share your
zone's name on the relay once a machine that holds it runs
`wyrd relay zone-add <the other machine's NKey>`
(`wyrd relay nkey-pubkey` prints it).

### Travel between zones

| You type | It goes to |
|---|---|
| `travel garage` | `garage` in your `my-zones` |
| `travel alice:kitchen` | `alice` in `contacts`, then their zone `kitchen` |
| `travel alice` | Alice's default zone |
| `travel did:wyrd:z6Mkp7x…:kitchen` | the full identity, for a first contact |
| `travel home` | reserved: back to where you came from |

When a name cannot be found, the error carries a short code:
`reserved_keyword`, `unknown_alias`, `unknown_label`, `malformed_did`,
`no_default_zone` or `ambiguous_label`.

**Meeting a new household.** The first contact is trust on first use. The
other household sends you its identity some other way, for example in a
message. You add it with `wyrd contacts add` and check the fingerprint before
you save it. The first time you arrive at their zone, the room header shows
their identity. Later visits just show `alice:kitchen`.

## Connect with another household

Federation takes a yes from both sides.

```bash
wyrd federate propose <zone>     # on alpha
wyrd federate accept <zone>      # on beta
wyrd federate status [--mesh]
wyrd federate list
wyrd federate revoke <zone>
```

The same actions exist as `wyrd zone federate|accept|revoke|status <zone-id>`.

`--mesh` shows a table of both sides' view of each agreement: `agree`,
`mismatch` or `unreachable`, with hints on how to fix each one. It is the
fastest way to find an agreement only one side has finished.

`WYRDSEKAI_FEDERATION_AUTO_ACCEPT=true` accepts incoming proposals as soon as
they arrive. It trusts **any** zone that can reach you, so use it only for
test setups. `WYRDSEKAI_API_URL` changes which server these commands talk to.

Once federated you get:

- messages across zones, such as `tell alpha.someone`,
- walking between the two households' zones,
- each side announcing what it can do,
- borrowing the other side's AI models, with usage counted.

A companion that moves to another zone arrives whole. Its soul manifest, the
record of who it is, travels with it, signed with the companion's own key. The
other zone checks that signature, and that the manifest matches the companion
and the pass it travels with. It refuses the move if anything is missing or
does not match, or if the two zones have no active agreement. The companion
then stays where it was. See [SOUL.md](SOUL.md).

Each agreement sets a daily allowance for borrowing the other side's AI models.
A request over the allowance is refused. So is a request from a zone with no
active agreement, or one not signed with the key that zone agreed with.

To browse the wider directory of zones, if you have chosen to publish yours:

```bash
wyrd discover [<url> | acct:<handle> | --did <d> | --tag <t>]
wyrd discover --capability <c> | --search "<text>" [--limit N]
```

Each zone that publishes does so itself, at `/.well-known/wyrd-zone`.

## Share a graphics card across the household

The main reason to have several machines is that one without a graphics card
can borrow the graphics card in another room.

A node borrows when all of this is true: borrowing is on, it has no graphics
card of its own, the other machine is a household member, and that machine
reports graphics memory above zero. It checks this regularly, and looks for
other machines again every 15 seconds.

A borrowed household graphics card gets priority 2. That is *below* the
machine's own processor, so the local processor stays the fallback when a
health check fails. The requests stay inside your household. This is sharing
within the household, not federation.

Two settings control it:

- `WYRDSEKAI_INFERENCE_HOUSEHOLD_SHARE` offers this machine's graphics card to
  the others. `wyrd setup` turns it on when it finds one, and off on a machine
  without one.
- `WYRDSEKAI_INFERENCE_HOUSEHOLD_BORROW` uses another machine's card. It is on
  by default everywhere, and `wyrd join` turns it on.

`WYRDSEKAI_INFERENCE_MODE` chooses where the AI models run:

- `local`, the default, runs them on this machine.
- `cloud` or `remote` runs none here and needs `WYRDSEKAI_INFERENCE_URL`.
- `zone` or `household` runs none here and uses another machine. Either pin
  one with `WYRDSEKAI_INFERENCE_URL=nats://<zoneId>`, or let borrowing choose.

`WYRDSEKAI_LLAMA_ENABLED=false` stops the local models in any mode.

```bash
wyrd inference status                  # backend, hardware recommendation, share/borrow state
wyrd inference share on|off|status     # the offer half
wyrd inference local [model-path]      # bundled llama-server (CPU or GPU)
wyrd inference remote <url>            # external HTTP endpoint
wyrd inference zone <zoneId>           # delegate to a federated peer zone via NATS
wyrd inference disable
```

`wyrd inference zone` needs an active federation agreement with that zone. The
other zone checks this too: it answers only zones it holds an active agreement
with, and only requests signed with that zone's key. A request from zone A to
zone B is answered by B's own models. B cannot pass it
on to zone C. Requests time out after 120 seconds by default.

## Check the health of the mesh

`wyrd status`, `wyrd doctor` and `wyrd state dump --summary` look at one node.
`wyrd version --mesh` asks every federated peer for its version and build. It
flags database schema mismatches and machines running different builds. It is
the fastest way to spot a node that missed an upgrade.

## How nodes find each other

There are three ways:

- **On your home network**, nodes announce themselves automatically using
  mDNS. The service type is `_wyrdsekai._tcp.local.`. Each node announces its
  node id, zone id, household id, HTTP port and NATS address. List them with
  `wyrd discover --lan`.
- **Seed nodes** are addresses you configure by hand.
- **A DNS record**: a TXT lookup at `_wyrdsekai.{domain}`.

A relay's *address* is announced. The relay's **token never is**. Joining
a household always needs an explicit yes.

`wyrd setup` writes this configuration: `WYRDSEKAI_BETWEEN_ENABLED=true`,
`WYRDSEKAI_NODE_ID=<hostname>`, `WYRDSEKAI_NATS_URL=nats://127.0.0.1:4222`,
`WYRDSEKAI_NATS_AUTO_START`.

## Why the Between works this way

A household is not a data centre. Its machines belong to different people, are
set up separately, and are out of reach most of the time. A phone on a mobile
network cannot be reached from outside. A laptop closes mid-sentence. A
machine with a graphics card is on when someone is using it. A design that
needs every node to connect to every other node would leave phones out, and
phones are where most people talk to their companion.

So the Between assumes the opposite. **It is enough if a connection can be
made in one direction.** No node needs to be reachable by every other. A node
can vanish without taking anything else down, and come back without any
ceremony. Trust comes in grades. Joining is an agreement between two parties,
not membership of a group. That is what makes federation between households
possible, and not only between machines that trust each other completely.

## Relays

A relay is a meeting point on the internet. Your zones connect *out* to it,
your phone connects *in* through it, and federation between households can
travel through it. It passes messages along and does not hold your world.

[RELAY.md](RELAY.md) covers everything about relays: joining one, putting your
companion on your phone, the public relay, and running your own.

## Ports

| Port | Used for |
|---|---|
| `4222` | NATS client connections: the household bus (encrypted, a login for every machine), and a zone's connection to a relay (the zone leg) |
| `4223` | NATS WebSocket, used by the phone apps (encrypted, a login for every phone) |
| `8222` | NATS monitoring, this machine only |
| `7422` | NATS leafnode, for linking to another NATS server |
| `7443` | Wyrdsekai HTTPS **and** secure WebSocket, for phones, browsers and other machines |
| `7070` | Wyrdsekai HTTP and WebSocket without encryption, for this machine only |
| `4443` | a relay's public TLS port |
| `9222` / `9280` | a relay's internal websocket and registration sidecar |

A relay can move its ports when it shares a machine with a zone. See
[RELAY.md](RELAY.md).

## For developers

**Zone identity.** A zone's canonical identity is a keypair plus a label:
`zoneId := (householdFingerprint, zoneLabel)`. The `householdFingerprint`
comes from the household's Ed25519 public key. It is written as a W3C DID
(`did:wyrd:z6Mk…`) and saved in `node-identity.json` on first boot. The
`zoneLabel` is unique within its own household, and on each relay among the
households registered there (first come). On the wire the canonical
form is `did:wyrd:{fingerprint}:{zoneLabel}`. As a NATS subject token it is
`{fingerprint}.{label}`.

One key serves three purposes: the peer id for discovery and routing, the
signer on every `BetweenEnvelope`, and the NATS credential that limits what the
zone may publish on a relay. Display name, icon and tagline are separate
manifest fields. The label is the identifier, not the pretty name.

**Why the actor system stays on one node.** Cluster membership would need the
two-way reachability a household does not have. See
[ARCHITECTURE.md](ARCHITECTURE.md) for that side of it.

**Transport.** The Between is a NATS mesh carrying Ed25519-signed envelopes.
Every machine checks each envelope against the sender's key before acting on
it: the household roster's key, a key pinned the first time an unenrolled
machine said hello, or the key a federation agreement pinned. It drops an
envelope that is unsigned, forged, from an unknown sender, more than 5 minutes
off its clock, or already seen. A pinned key changes only through a rotation
message signed by the old key.
Subjects inside a household follow one grammar:

```
between.{zoneId}.{sourceNodeId}.{targetNodeId}.{layer}.{topic}
```

Broadcast puts `*` in the target position. Shipping examples:
`cluster.hello`, `cluster.heartbeat` (10s), `cluster.leaving`,
`actor.room.{roomId}`, `probe.ping`/`probe.pong`, `probe.capabilities`,
`rooms.announcement`, `rooms.claim`. A relay leg bridges `between.{zoneId}.>`,
and the relay lets a zone's registration read only its own `between.{zoneId}.>`
(plus other zones' capability announcements), so a zone only ever sees its own
subtree.

Traffic between households uses a separate namespace:
`federation.{zoneId}.gate.*`, `federation.{zone}.tell`,
`federation.inference.{targetZone}.complete`. During the current migration a
canonical `federation.{fingerprint}.{label}.gate.*` form is subscribed
alongside the legacy one.

The relay control plane and the phone tunnel use `wyrd.zone.{zoneId}.…` and
`wyrd.tunnel.{zone}.…`. Every relay registration is bound to one zone name,
and the relay's permissions let it use only that zone's subjects: it can send
to another zone's federation mailbox, but it reads only what is addressed to
its own zone, and only its own reply inbox. A household's phones get their own
relay user with the same limit. `wyrd.discover.zone` is shared by design: a
phone asks it to learn a zone's name. `wyrd.discovery.capabilities` and
`wyrd.inference.capabilities` stay on the household's own bus; the relay does
not carry them.

**Inference sharing** between zones uses `federation.inference.{targetZone}.complete`
for the request and `federation.inference.stream.{askingZone}.{id}` for the
answer, so only the asking zone's relay user can subscribe to it. The request
text and the answer are not sealed: when they cross a relay, the relay operator
can read them. Cross-zone inference requests are pinned to a named local
backend on arrival.

The wire protocol is in [PROTOCOL.md](PROTOCOL.md).
