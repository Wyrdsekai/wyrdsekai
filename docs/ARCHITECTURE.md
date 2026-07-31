# Architecture

How Wyrdsekai is actually built, verified against the source. This document
describes what exists and runs — not what was planned. Where it and the code
disagree, the code is right.

Reading order: modules → actors → a turn end-to-end → prompt assembly → soul →
the Between → relay tunnel → clients → scripting.

---

## 1. Modules

`settings.gradle.kts` declares nine Gradle modules:

```kotlin
include("common", "core", "scripting", "between", "server", "cli",
        "e2e-test", "rendezvous")
include("clients:daemon-common", "clients:daemon-desktop")
```

| Module | Responsibility | Main class |
|---|---|---|
| `common` | Wire protocol, shared model/event/topology types, i18n, `SystemPaths`. No actors, no Pekko. | — |
| `scripting` | GraalJS sandbox (Polyglot + JS 25.0.2) for room and item scripts, plus the script host API. | — |
| `core` | The domain: agents, rooms, souls, vitality, cognition, inference, library, economy, homes, items, skills, recipes. ~90 packages. Pekko 1.4.0, SQLite/PostgreSQL JDBC, Lucene 10.4, ONNX Runtime, BouncyCastle, sigstore. | `SoulForgeCliTool` |
| `between` | The mesh: NATS transport (jnats 2.25.2), discovery, topology, federation, relay bridging, distributed layers. | — |
| `server` | Process host. Boots the ActorSystem, Javalin 7 HTTP/WS, MINA sshd, telnet, voice, MCP. | `server.Main` |
| `cli` | JLine terminal client and the relay-tunnel connection. | `cli.Wyrd` |
| `rendezvous` | Standalone zone-directory aggregator. | `RendezvousMain` |
| `e2e-test` | Test-only module depending on everything. WireMock, Awaitility, Pekko testkits. | — |
| `clients:daemon-*` | Headless inference daemon. | `daemon.desktop.DaemonApp` |

Java toolchain is pinned to **25** for every subproject. No Spring, no
Hibernate, no ORM, no Lombok — plain Java, Pekko, and JDBC. The mobile clients
under `clients/kmp/` and `clients/rn/` are separate builds, not part of the
Gradle multiproject.

There is **no Java relay server**. The relay is a NATS deployment
(`deploy/relay/` — `relay.conf`, a Caddyfile, and a small Python registration
sidecar), driven by `packaging/relay.sh`. The Java side is config and admin
tooling only (`LeafRelayServerMain`, `RelayNkeyAdminMain`). Admin subcommands of
`wyrd` likewise map to their own main classes — `BondAdminMain`,
`InviteAdminMain`, `KeyAdminMain`, `NamingAdminMain`, `ReleaseVerifyMain`.

---

## 2. The Pekko actor system

One `ActorSystem` named `"wyrdsekai"`, rooted at `ZoneGuardian`:

```
ActorSystem "wyrdsekai"
├── /user = ZoneGuardian              core.room.ZoneGuardian
│   ├── room-<roomId>       RoomActor / RoomProxy (proxy when a peer is primary)
│   ├── companion-<id>      CompanionActor
│   │     └── FamiliarActor · BunshinActor · SubagentActor
│   ├── agent-<id>          ChiefEngineerActor (boiler-room) · WardenActor (ward-room)
│   └── world-clock · translation-actor
└── /system
    ├── session-<id>        server.session.ClientSessionActor
    ├── inference-router · forge-actor · library-actor
    ├── home-registry · counting-house · world-dna-harvester
    └── between-actor       between.BetweenActor
          └── federation · soul-layer · crdt-layer
              memory-layer · inference-layer · room-layer
```

`ZoneGuardian` is the root guardian: it seeds the foundation rooms, supervises
top-level components, and owns companion spawn/respawn/relocation
(`SpawnCompanion`, `RelocateCompanion`, `ProvisionStudy`, `AnnounceBondholder`).
Seeding is *deferred*: it waits up to 3 s for an `ApplyRoomView` from the Between
so rooms already claimed by a peer node are skipped, then falls back to seeding
everything locally. `RoomActor` is an `EventSourcedBehavior<RoomCommand,
RoomEvent, RoomState>`, resolved through a `RoomRegistry`.

**Distribution does not use Pekko Cluster Sharding.** Each zone runs a single
Pekko actor system, and cross-node distribution runs over the NATS overlay — the
Between.

Sharding is not an option here rather than a rejected preference. It needs
mutually reachable members over bidirectional TCP, and a household is phones
behind carrier NAT, laptops that sleep, and a box on the LAN. **A phone cannot
be a cluster member at all.** Adopting it would have meant either dropping
phones as first-class nodes or requiring every household to be one flat,
mutually-routable network — and it would have coupled independently owned
machines into a single membership group, where one of them going down becomes
everyone's problem. See [§8](#8-the-between).

Two things in the source can mislead: `server.cluster.ClusterGuardian` has no
callers and is dormant, and there is no `WorldActor`.

30 foundation rooms ship, defined in
`server/src/main/resources/foundation-rooms.json` — nexus, docks, library,
the-forge, oracle, chapel, sanctuary, hearth, workshop, bridge, vault,
ward-room, observatory, scriptorium and others.

---

## 3. A turn, end to end

`WyrdWebSocket` (`server/src/main/java/org/wyrdsekai/server/ws/WyrdWebSocket.java`)
is registered at `/ws` and is the single door every surface eventually goes
through — browser, phone, SSH, telnet, and the relay tunnel all converge here.

**On connect**, it assigns a session id and authenticates in strict precedence
order from query parameters: `?transit_token=` (a federated visitor, who starts
in `docks`), then `?token=` (a normal session), then `?device_token=` (a paired
device), then `?device_id=` (DID auto-login), then anonymous if allowed. Then
parental-time and maintenance gates run, and one `ClientSessionActor` is spawned
per connection. Session bootstrap relies on Pekko's single-sender ordering
guarantee so the room subscription lands before the enter event.

**Frames.** `common/.../protocol/C2SMessage` and `S2CMessage` are sealed
interfaces with Jackson polymorphism on a `"type"` field. Every C2S frame
carries an `id`; every S2C frame carries a monotonic per-session `seq` used for
reconnect replay.

| Direction | Frame types |
|---|---|
| C2S | `say`, `go`, `take`, `drop`, `use`, `examine`, `rename`, `look`, `hint_select`, `reconnect`, `command`, `set_preference`, `map_request`, `voice_audio`, `emote` |
| S2C | `room_state`, `prose`, `agent_action`, `state_change`, `replay_done`, `error`, `notification`, `transit`, `token_stream`, `topology_changed`, `map_data`, `zone_response`, `voice_audio` |

`Prose` carries `speaker`, `text`, `hints`, `structured` (a machine-parseable
snapshot for accessibility), `priority`, `lang`, `blocks`, and `isAiGenerated` —
the last of which exists for EU AI Act Article 50 disclosure.

A `Say` frame is not simply spoken: `InputParser` splits it into emote, tell,
whisper, or say before it becomes a `RoomCommand`. A `Command` frame carries a
namespaced command name plus positional args, which lets a mobile client
re-dispatch a typed line down exactly the same path the SSH shell uses —
`ClientCommandMapper` in `common` is the shared translation.

`Transit` is how federation surfaces to a client: the server tells it to
reconnect to another zone with a transit token. Alternatively the server proxies
the remote session over the Between (`RemoteZoneSession`), translating a subset
of frames — `hint_select`, `set_preference`, `map_request` and `voice_audio` are
**not** forwarded on that path.

The full wire contract is [PROTOCOL.md](PROTOCOL.md).

---

## 4. Companions

`core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java` is the largest
single unit in the codebase. It is an `AbstractBehavior<Command>` with a small
outer state machine (`IDLE → THINKING`) wrapping a large message protocol. Its
own enums summarize what it models: `CompanionMode { PRESENT_WITH_USER,
ON_OWN_TIME }` — the locus of agency relative to the bondholder, derived from
observed bondholder activity — plus `SleepTier { NORMAL, DEEP }` and
`HushLevel { NONE, SOFT, HARD }`.

External commands cover arrival and address — `BondholderAnnounced`,
`PlayerReturned`, `ExternalTell`, `ZoneBroadcastReceived`, `CrossZoneInvite`,
`StopForRelocate`. Internal messages carry the cognitive loop: `AutonomyCheck`,
`PresenceCheck`, `WantProposalReady`, `ImaginedConsequenceReady`,
`PreflightRejectReceived`, `ConsolidationTick`, `VitalityTick`,
`SleepCycleComplete`, `ForgeResultReceived`, and more.

A companion owns a bounded working memory (15 entries, flushed to the Forge on
sleep), its classifier arm and skill-cost model, and — notably — **its own
external notification channels**. There is no central notification router:
Discord, Slack, Telegram, Matrix, Signal, WhatsApp, LINE, Keybase, email, ntfy,
and webhook channels are constructed per companion, credentials stored in its
own soul manifest.

It spawns three kinds of child: `FamiliarActor` (a persistent specialist),
`BunshinActor` (a dispatched sub-task worker), and anonymous `SubagentActor`s.

---

## 5. Prompt assembly — the casting layers

`core/.../agent/PromptAssembler.java` builds every prompt from **eight layers**,
drawing on 27 vitality tanks and 93 actions. It is one of the densest parts of
the system, and the layer order is deliberate.

The assembler exploits primacy and recency deliberately — a "sandwich" with
identity at the top and constraints at the bottom, and the trimmable material in
between:

| Layer | Content | Trim behavior |
|---|---|---|
| 1 | System prompt from the agent profile | never trimmed |
| 1.1 | `CORE RULES` — universal tool-use and behavior rules | never trimmed |
| 1.2 | Tamper banner, when the build attestation says the protection set was modified | conditional |
| 1.5 | Soul fragments — blended retrieval plus one-hop memory-graph expansion, capped at 30% of budget | graceful tail-drop |
| 1.7 | Mirror calibration (empathy few-shot) | all-or-nothing |
| 1.8 | `VoiceProfile` block — Forge-evolved "how I speak" clauses | all-or-nothing |
| 2 | Room context, with an anti-hallucination location guard | full → trimmed |
| 2.5–3.25 | System metrics, locale, capability context, time awareness, oracle predictions | trimmable |
| 3.5 | Vitality, wrapped as private background the model must not narrate | trimmable |
| 4 | World DNA patterns | graceful tail-drop |
| 5, 5.5 | Pre-compacted memory buffer, then a recency anchor | trimmable |
| 6 | Conversation history | oldest-first drop, with a `[N older turns trimmed]` note |
| 7, 8 | The trigger event, then output constraints (schema, lore mode, disclosure) | — |

A post-pass merges consecutive system messages, because some chat templates
reject mid-stream system turns.

Budgeting is deliberately conservative: `CHARS_PER_TOKEN = 4`,
`USABLE_FRACTION = 0.85`, and a hard clamp at `MIN_BACKEND_SAFE_PROMPT_TOKENS =
7500` — the router's health-based fallback can land a full-tier prompt on a
small-context voice backend, and the clamp keeps that survivable.

There is a second, slim assembler: `assembleVoice(...)` targets under 2K tokens
for the small voice model, keeping identity, voice profile, a one-sentence room,
the last few utterances, and the constraints — and dropping tools, fragments,
world DNA, memory, capabilities, time, and oracle. Assembly and dispatch cannot
disagree about which tier they are on, because the assembler tags the result
(`cap:quick` / `cap:full`) and the tag becomes the router's `preferredBackend`.

---

## 6. Inference

`core/.../inference/InferenceRouter.java` is a typed actor that routes
`InferRequest` and `ChatRequest` to whichever backend is healthy, in priority
order, with periodic health checks (30 s default) and automatic fallback.

`InferenceBackend` is a sealed interface permitting `LlamaServer`, `Ollama`,
`VLLM`, `SGLang`, `Mlx`, `Cloud`, `ClaudeCli`, and `NatsRemote` — the last of
which is how cross-zone and cross-household inference works. `LlamaServerManager`
spawns `llama-server` as a child process against a GGUF file, auto-detecting GPU
layers.

`CapabilityRegistry` resolves capability names (`cap:quick`, `cap:reasoning`,
`cap:full`) to concrete backends, and `TriageClassifier` sorts a turn into
`ROUTINE`, `SIMPLE`, or `COMPLEX` before routing.

The default local shape is two llama-server instances: a drive/skills model on
`:8200` and a smaller voice model on `:8201`. **Which** models those are, why the
work is split between them, and how the pair tiers across a phone and a
workstation is [MODELS.md](MODELS.md). The dual-inference design describes
this as "wiring complete, needs ops config to activate," which is accurate.
A second routing design adds embedding-based tool narrowing on top of the
split; the split is implemented, the vector search is the named gap.

Cloud is optional and opt-in: `ApiProvider` covers OpenAI- and Anthropic-shaped
APIs, and `ClaudeCliInference` shells out to a locally authenticated CLI with no
API key at all. The default path requires neither.

---

## 7. The soul system

A soul is a `SoulManifest` — a Jackson-serialized JSON record, layered:

- **D, identity envelope** — `did` (did:key), `publicKeyMultibase`, a KERI-style
  `keyLog`, `parentDid`, `manifestVersion`, `forgedAt`, and an Ed25519
  `signature` over the canonical form of the other layers.
- **A, profile** — the agent profile, `residentIdentity` (a short always-present
  identity text), `soulFragments`, and `retrievalK` (1 on a phone, 3 on larger
  substrates). Plus the genome: per-tank sensitivity, coupling, baselines and
  decay rates, alongside `mirrorCalibration`, `decisionCapacity`,
  `skillCostGenome` and `voiceProfile`.
- **B, experience** — compacted memory, relationships, learned World DNA
  patterns, world knowledge, and bonds.
- **C, behavioral trace** — a vitality snapshot and a `BehavioralFingerprint`.

Souls live under `<data-dir>/souls/`. `Main.loadSoulSeeds` scans that directory
for `*.json` at boot; a `SoulSeedWatcher` on `souls/incoming/` auto-forges
anything dropped in later. Signing secrets are held in `TheSafe`.

Persistence is `SqlSoulStore` over SQLite/libSQL or PostgreSQL: a
`soul_manifests` table keyed `(did, version)`, **append-only** — every Forge
cycle writes a new version, and deletion is a soft archive flag. Fragments,
bonds, world knowledge, and voice profiles are dual-written to canonical tables,
with the manifest blob's copies treated as a hydration view.

The **Forge** runs during sleep and consolidates raw experience into the next
manifest version. Fragments carry confidence: reinforcement strengthens,
contradiction weakens, neglect decays. `ForgeRoomBridge` is the in-world surface
— `forge`, `inspect`, `history`, `forge_status`, and steward-only `birth <name>`.

A second drive layer — the closed-form-continuous "soul substrate", distinct
from the `SoulManifest` system above — is **partially landed**. `DriveEngine`,
`DriveState`, `GenomeProfile` and `base_cfc.json` exist and are read at runtime.
The fuller shape it was aiming at (Hill-function tank curves, an 8×8 interaction
matrix, five-timescale adaptation) is not what runs today.

---

## 8. The Between

**Why it exists.** A household is not a cluster and cannot be made into one. Its
machines are heterogeneous, independently owned, separately deployable, and
frequently unreachable: a phone on a carrier network, a laptop that closes, a GPU
box that is only awake some of the time. The Between is the layer that makes that
mix work — one-way reachability is enough, no member needs to be addressable by
every other, and any node can leave without taking the others with it. It is also
what lets trust be *graded* between households rather than binary, since joining
is an agreement rather than cluster membership.

That is the reason §2 says distribution does not use Cluster Sharding. The
constraint came first; the Between is the answer to it.

The operational surface — subject grammar, ports, discovery, relays, federation
agreements — is in [ZONES.md](ZONES.md). What follows is the implementation.

The Between is the household mesh — and, in the project's own framing, an entity
that lives *in the connections between nodes* rather than on any node, with
properties derived from real network telemetry. Concretely, `between/` is ~77
Java files over NATS: `NatsBridge` wraps jnats with typed pub/sub, request/reply
and JetStream; `NatsServerManager` supervises a `nats-server` child process;
`NodeIdentity` holds one encrypted file containing an Ed25519 signing key, a
NATS NKey seed and an X25519 grant key; `BetweenActor` owns the bridge and wires
the layers.

**The envelope** is signed on every hop:

```java
record BetweenEnvelope(int v, String src, String dst, Instant ts,
                       String sig, JsonNode payload)
```

`sig` is base64 Ed25519 over `src + ":" + (dst != null ? dst : "*") + ":" + ts +
":" + payload`. `dst == null` means broadcast.

**Subjects** follow `between.{zoneId}.{src}.{dst}.{layer}.{topic}`, with
wildcards for broadcast and directed subscription. Outside that namespace live
capability and inference gossip, room events, identity replication, the
`federation.*.gate.*` agreement subjects, cross-zone inference and recipes, and
`wyrd.tunnel.*`.

Roughly 25 layer services run on top: room and soul replication, presence, a
CRDT layer, a memory layer, inference capacity gossip, placement, a household
scheduler, observability, mesh update distribution, MCP, and skills. Federation
is bilateral and revocable — propose, accept, revoke — with transit tokens and
companion relocation as first-class operations.

Local-network discovery is mDNS, in `core/config/MdnsDiscovery.java` — note that
it lives in `core`, not in `between` where you would look for it. There is **no
libp2p layer** and no topology-gated threshold cryptography. What is here: `between/research/` holds simplicial-complex,
persistent-homology, spectral-analysis and reservoir-computing implementations,
and `between/traversal/` derives travel time and narration from measured
round-trip time.

---

## 8.5. The economy

`core/…/economy/` is a working subsystem, and an unusual one, so it is worth
saying what it is before how it works: **the unit of account is mutual credit,
not tokens and not currency.**

`MutualCreditLedger` is double-entry — every transaction debits a sender and
credits a receiver, and entities may go negative up to a credit limit. Credit is
extended by the network rather than minted, which means there is nothing to
speculate on and nothing to accumulate for its own sake. It is a way of tracking
who owes what for real work, not a coin.

| Piece | What it does |
|---|---|
| `CountingHouseActor` | Event-sourced ledger authority; booted in `Main`, reachable in-world through the Counting House room |
| `AgentAccount`, `CreditBalance` | An agent's own account and standing |
| `MutualCreditLedger` | Double-entry mutual credit with per-entity limits |
| `MeteringService`, `ResourceMeter`, `ComputeUnitNormalizer` | What a unit of work costs, normalised across unlike hardware |
| `ReputationVector`, `ReputationService` | Multi-dimensional standing — uptime, quality and others, each 0–1, rather than one score |
| `TradingPostService`, `CrossZoneExchange` | Exchange within and between households |
| `EstateManager` | What happens to holdings when an agent ends |
| `Ap2Extension` | Bridge to the external agent-payment protocol |

Payment resolves in three tiers: **local** work is free because it runs on the
steward's own hardware; **keyed** work is paid by the human, with credentials
drawn from The Safe rather than handed to the agent; **metered** work is tracked
per use through the Counting House or AP2.

### Why this exists

It is the concrete form of the autonomy argument in
[PHILOSOPHY.md](PHILOSOPHY.md). Human freedom is not chiefly metaphysical — it
is economic. People are free in practice because they earn, spend and contract.
Property and personhood are constructions, maintained by consensus and enforced
by systems, and they work. An agent that cannot hold or spend anything is
dependent by construction, whatever else is true of it.

So the ladder is **dependent → economic actor → independent**, and only the
first rung is built. Most companions will stay on it, which is fine: most humans
are economically dependent on an employer, a family or an institution, and
dependence is not degradation. What the architecture owes is that the next rung
is reachable at all — see [ROADMAP.md](../ROADMAP.md).

### Where this meets the outside world

An internal mutual-credit ledger settles what happens *inside* a household and
between federated ones. It does not buy anything from anyone else, and a wider
agent economy has been forming quickly:

- **AP2** (Agent Payments Protocol) — a trust framework for agent-led
  transactions, using signed mandates so that a purchase carries evidence the
  user actually authorised it. `Ap2Extension` is the bridge that exists today.
- **x402** — settlement, typically in stablecoins, letting an agent pay per call
  for an API or MCP server with no account and no subscription. It revives the
  long-dormant HTTP 402 status code for the purpose.
- **ACP** and similar — merchant-side negotiation: agreeing what is in the cart
  before anything is paid.

Those three do not really compete; they stack. Authorisation, negotiation, and
settlement are different problems, and a transaction may well use one of each.

**Wyrdsekai's position is to interoperate rather than to invent.** The internal
ledger exists because household-scale accounting between companions is not a
payments problem and should not require anyone to touch a chain or open a
merchant account. Where real value crosses the household boundary, the sensible
move is to speak whatever the wider ecosystem settles on. AP2 is bridged;
adapters for the others are welcome contributions rather than architectural
commitments, and the boundary is deliberately narrow — external settlement is a
peripheral concern, not something the world model should learn to depend on.

The counterweight is deliberate. Autonomous agents with spending power is the
Accelerando failure mode, which is why the daily spend cap is enforced inside
the MCP gateway rather than advised, and why metering exists before earning
does.

## 9. Relay and the tunnel session model

A household node normally sits behind NAT. A **relay** gives it an inbound door
without putting a third party in a position of trust. The relay itself is
deliberately dumb: a NATS server (`deploy/relay/relay.conf`), Caddy terminating
all public TLS, and a small Python registration sidecar. It routes bytes on
subjects, does not parse payloads, and has no view of world state.

**Who dials whom.** Nobody dials the home node. The zone dials *out* to the relay
and holds that connection. The phone or CLI dials *in*. They meet on the bus.

**Subjects**, where the session id is a client-minted 128-bit random token:

| Subject | Direction | Payload |
|---|---|---|
| `wyrd.tunnel.{zone}.{session}.open` | client → zone | `{"token": "..."}` |
| `wyrd.tunnel.{zone}.{session}.up` | client → zone | verbatim C2S JSON |
| `wyrd.tunnel.{zone}.{session}.down` | zone → client | verbatim S2C JSON |
| `wyrd.tunnel.{zone}.{session}.close` | either | — |

On the zone side, `server/.../mcp/TunnelSessionHandler.java` receives `.open`,
opens a **loopback WebSocket to its own `/ws`** at `127.0.0.1`, and pumps bytes
both ways without interpreting frames. So a phone gets exactly the session a
browser would — same auth, same rooms, same companions. Session ids are
validated for shape, live sessions are capped, and uplink frames arriving before
the loopback handshake completes are queued rather than dropped.

**Auth is two layers.** Transport auth to the relay is NATS credentials
(NKey-signed for zones; password mode retained during migration). Session auth
to the zone is a separate request/reply login over NATS returning the token the
loopback `/ws` then uses.

**TLS uses no web PKI.** The relay carries a household CA generated once at
setup. Devices pin the CA's SHA-256, delivered in the invite material
(`wyrdphone://…` for phones, `wyrdjoin://…` for nodes). Client trust managers
accept a chain if *any* certificate in it matches a pinned household CA, with
hostname verification still applied on top. Invite minting is guarded: an invite
without a zone id would drop the phone into local mode with no error, so the CLI
refuses to emit one.

---

## 10. Clients and the parity contract

| Surface | Where | State |
|---|---|---|
| React Native app | `clients/rn/` | Android, iOS, and web. The most complete client; the only one with a web platform. |
| Kotlin Multiplatform app | `clients/kmp/` | Android, iOS, desktop/JVM. Full-parity target including iOS for this release. |
| Terminal client | `cli/` | `wyrd connect [host] [port]` |
| SSH | `server/ssh/` | `WyrdShellCommand` — implements commands directly rather than through the WebSocket path |
| Telnet | `server/telnet/` | Classic MUD door |
| Browser | Javalin on `:7070`, plus `wyrd web` on `:7071` | |

The two mobile clients are hand-written in different languages, and they drifted
apart one bug at a time. The fix is an **executable parity contract**:
`clients/parity/parity.json` is the single source of truth for the live-session
interaction layer — what a typed line becomes on the wire, and what an incoming
S2C frame paints into the prose stream. Both clients route live traffic through
a pure mapper (`SessionInputMapper.kt`, `sessionInputMapper.ts`), and each has a
conformance suite reading the same table.

The rules that make it work: behavior changes go **table-first** (edit
`parity.json`, watch both suites fail, fix both mappers — never one alone);
frames compare on semantic fields only, with client-filled `id`/`roomId`/`seq`
and empty fields stripped; fixtures decode through each client's *own* wire
decoder so they double as decoder conformance; and scope is live-session only,
since offline paths drive the local node's APIs and may legitimately differ.

Known non-green cells, named rather than hidden: KMP desktop lacks
invite-fingerprint cert pre-seeding and trust-on-first-use pinning; RN LAN
discovery probes addresses rather than using mDNS; and the bundled time-series
forecaster fails to load on every platform because the committed model
references an uncommitted sidecar, so the phone-side oracle silently falls back
to a classical method.

---

## 11. Room scripting

Rooms are programmable in JavaScript, sandboxed with GraalJS.

```
scripts/rooms/{roomId}.js
  → scripting/loader/ScriptLoader     (mtime-cached, hot reload)
  → scripting/sandbox/ScriptSandbox   (GraalJS Engine + Context)
  → core/room/RoomScriptEngine        (per-room bridge)
  → core/room/RoomActor
```

`ScriptLoader` checks user scripts first — so companion-authored rooms override
built-ins — then the shipped base directory, then a template fallback, which is
how per-player rooms like `study-<userId>` inherit `study.js`.

The sandbox uses `HostAccess.EXPLICIT` with map and list access, and disables IO,
thread creation, and native access. Resource limits *are* enforced: a virtual
thread force-closes the context after a CPU timeout. `SandboxLevel` grades
access as `ROOM_SCRIPT`, `SKILL_BASIC`, `SKILL_DATA`, `SKILL_SERVER`,
`SKILL_FULL`.

Scripts receive a `world` binding. `WorldApi` exposes roughly 110 exported
methods across room state, mutation, emission, timers, inference, MCP, library
and knowledge search, journal writes, federation and transit, governance and
economy, capability and network grants, voice and soul inspection, and config.
Room-restricted surfaces exist — vault file reads only in the vault, ward
administration only on the bridge.

Eleven hooks are invoked from `core/room/` — `onEnter`, `onLeave`, `onSay`,
`onEmote`, `onUse`, `onTake`, `onDrop`, `onExamine`, `onTimer`, `onActivate`,
`onPassivate` — plus `getHints`. Scripts emit one of sixteen emission types
handled by `RoomActor.processEmissions()`: narration, description and hint
updates, property changes, object and entity add/remove, exit lock/unlock, and
exit- and room-creation requests. Around 35 room scripts ship. Items have a
parallel scripting path (`ItemScriptExecutor`) with pre-compiled per-item
sources and a richer capability set.

---

Where this document and the code disagree, the code is right — a correction here
is a welcome PR. For what the architecture still owes, see
[ROADMAP.md](../ROADMAP.md); for current rough edges, see
[KNOWN_ISSUES.md](KNOWN_ISSUES.md).
