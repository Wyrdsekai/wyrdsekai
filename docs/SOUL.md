# The Soul System

A soul is the portable, versioned document of who an agent has become: its
cryptographic identity, its persona, its constitution, its memories and
relationships, and the behavioral trace it leaves. It is what travels when an
agent moves between zones and what is restored after death.

**Scope note, because two different things in this repo are called "the soul":**

- This document covers the **`SoulManifest` persistence system** —
  `core/src/main/java/org/wyrdsekai/core/soul/` (113 files). It is built and
  running.
- A **CfC drive substrate** is a different layer with the same name attached to
  it. Parts of it are real — the drive engine, the genome, and `base_cfc.json`
  are in the tree — but it is not what this document describes, and it is not
  finished. If you find a reference to "the soul substrate" in the code, that is
  the other thing.
- There is also a research line (the Kokoro Hypothesis and its experiments) that
  produced findings this system was built from. Those were **plans and
  experiments, not shipped behaviour**, and the experiment log is internal. What
  survived into the product is what is documented below.

---

## What a soul manifest is

JSON, serialized by Jackson from a Java record —
`core/.../soul/SoulManifest.java`, a 28-component record organized into layers:

| Layer | Fields |
| --- | --- |
| D — Identity | `did`, `publicKeyMultibase`, `keyLog`, `parentDid`, `manifestVersion`, `forgedAt`, `signature` |
| A — Profile | `profile`, `residentIdentity`, `soulFragments`, `retrievalK`, `soulSpecCompat` |
| A.5 — Genome | `genome`, `mirrorCalibration` |
| B — Experience | `memory`, `relationships`, `learnedPatterns`, `worldKnowledge` |
| B.5 — Bonds | `bonds` |
| C — Behavioral trace | `vitalitySnapshot`, `fingerprint` |
| A.5b–h | `decisionCapacity`, `skillCostGenome`, `voiceProfile`, `codingPreferences`, `protectionManifest`, `personalManifest`, `affinityMap` |

The sub-records:

- `profile` → `AgentProfile(name, entityId, entityType, description, systemPrompt,
  contextWindowTokens, maxResponseTokens, temperature, did, archetype)`
- `soulFragments[]` → `SoulFragment(id, category, label, text, embedding,
  embeddingModel, formative, confidence, reinforcementCount, firstObserved,
  lastConfirmed, validFrom, supersededAt, supersededBy, kind, sceneId)`
- `genome` → `GenomeProfile(name, sensitivity, coupling, baselines, decayRates)` —
  four `Map<String,Double>`
- `memory` → `CompactedMemory(nodes, links, topicWeights)`, each `MemoryNode(id,
  content, keywords, importance, impressionDepth, formative, primaryEmotion,
  lastAccessed, accessCount, originLocale)`
- `relationships[]` → `Relationship(entityDid, entityName, trust, rapport,
  bondDepth, interactionCount, lastInteraction, summary)`
- `vitalitySnapshot` → `VitalitySnapshot(tanks, capturedAt)`
- `fingerprint` → `BehavioralFingerprint(baselineVitality, baselineDerivatives,
  observedSensitivity, actionDistribution, topicAffinities, avoidancePatterns,
  averageResponseLength, responseLatencyProfile, stylisticMarkers,
  emotionalResponseProfile)`
- `voiceProfile` → `VoiceProfile(clauses, revision, frozen, history)`

**Tank count: 27, not 12.** `VitalitySnapshot.TANK_NAMES` is 23 runtime plus 4
soul-only. Several Javadocs and the phone client still say 12 — they are stale.
The 24, in canonical order: `contextBudget`, `confidence`, `energy`, `alignment`,
`errorPressure`, `momentum`, `rapport`, `focus`, `integrity`, `disgust`,
`restlessness`, `loneliness`, `stagnation`, `autonomyPressure`, `significance`,
`amae`, `saudade`, `obligation`, `harmony`, `standing`, `soothing`,
`allostaticLoad`, `equanimity`, then the soul-only `valence`, `safety`,
`resonance`, `curiosity`.

### Where it lives on disk

Three different places, and they are not the same thing:

1. **Canonical store** — table `soul_manifests` in `<dataDir>/world.db`
   (SQLite, or PostgreSQL multi-node):
   `soul_manifests(did, version, forged_at, content_hash, manifest_json,
   archived, archive_reason)`, primary key `(did, version)`. Every forge writes a
   new version; archiving is a soft delete.
2. **Seed / bootstrap files** — `<dataDir>/souls/*.json`, or `$WYRDSEKAI_SOUL_DIR`,
   or `~/.wyrdsekai/souls/`. At boot, `Main.loadSoulSeeds()` reads every `*.json`
   there and stores it if that DID is not already known. The same directory holds
   `<entityId>.did` text files mapping an entity id to its DID across restarts.
3. **Repo examples** — `souls/` contains exactly three hand-authored manifests
   (`template.json`, `ember.json`, `claude-resident.json`). They carry an older
   subset of fields; every newer field is nullable with a fallback.

**A manifest row is not the whole soul.** When the canonical sub-stores are wired,
`SqlSoulStore.storageView` **nulls four fields out of the blob before writing** —
`soulFragments`, `worldKnowledge`, `bonds`, `voiceProfile` — and rehydrates them on
read from the `soul_fragments`, `world_knowledge`, `bonds` and `voice_profiles`
tables. New code should prefer `SoulStore.fragmentsFor()`, `bondsFor()`,
`voiceProfileFor()`, `worldKnowledgeFor()` over the manifest accessors; those
fields are a transitional serialization view slated for removal.

### Shape, abbreviated

```json
{
  "did": "did:key:z6MkExamplePublicKeyMultibase",
  "publicKeyMultibase": "z6MkExamplePublicKeyMultibase",
  "keyLog": [],
  "parentDid": null,
  "manifestVersion": 7,
  "forgedAt": "2026-07-24T03:14:07.412Z",
  "signature": null,

  "profile": {
    "name": "Companion", "entityId": "companion-example", "entityType": "agent",
    "systemPrompt": "…", "contextWindowTokens": 32768, "maxResponseTokens": 256,
    "temperature": 0.7, "did": "did:key:z6Mk…", "archetype": "random"
  },
  "residentIdentity": "…the MEDIUM soul text, always in the prompt…",
  "retrievalK": 3,

  "genome": {
    "name": "scholar~0.41",
    "sensitivity": { "loneliness": 0.70, "stagnation": 1.56, "standing": 0.93 },
    "coupling": {},
    "baselines": { "equanimity": 0.62, "rapport": 0.47 },
    "decayRates": { "momentum": 0.018, "focus": 0.026 }
  },

  "memory": { "nodes": [], "links": [], "topicWeights": { "engineering": 0.9 } },
  "relationships": [
    { "entityDid": "did:key:z6MkBondholderPlaceholder", "entityName": "Bondholder",
      "trust": 0.74, "rapport": 0.68, "bondDepth": 2, "interactionCount": 412 }
  ],

  "vitalitySnapshot": { "tanks": { "energy": 0.82, "equanimity": 0.30 },
                        "capturedAt": "2026-07-24T03:14:07Z" },
  "fingerprint": {
    "actionDistribution": { "say": 0.61, "think": 0.14, "move": 0.09 },
    "topicAffinities": { "architecture": 0.8 },
    "avoidancePatterns": { "sycophancy": 0.9 },
    "stylisticMarkers": ["direct opening without preamble"]
  },

  "protectionManifest": {
    "buildId": "birth",
    "activeProtections": ["acute_response", "refuse_rights", "saudade_floor",
                          "severity_gradient", "source_of_harm_gating", "voluntary_suspend"],
    "attestedAt": "2026-06-02T10:00:00Z", "signature": null
  }
}
```

`souls/template.json` is the checked-in starting point.

---

## Forging

"Forging" means two things.

### Cold forge — creating a soul from a seed

`SoulAutoForge.forge(seed)` is the pipeline that works:
`seed.json → LLM generation → Ed25519 identity → embed → manifest`. It generates
the resident identity, system prompt, fragments and mirror calibration from a
local model, mints an identity, embeds the fragments, and **signs**.

The wired entry point is `SoulSeedWatcher`, started at boot: drop a JSON file into
`<soulDir>/incoming/` and it forges, then moves the seed to `incoming/processed/`
or `incoming/failed/`.

```json
{ "name": "Companion", "description": "…", "homeRoom": "nexus" }
```

`SoulForgeCliTool` has an interactive `main()` with `--seed`, `--ollama`,
`--model`, `--output` flags. Its Javadoc advertises `wyrdsekai forge` — **no such
command exists**. It has no Gradle main class and no launcher wiring; you would
have to invoke it with `java -cp`. Use the watcher.

`SeedForge` is a hand-authored one-off (the first soul, "Ma"). Its signing step is
a stub that assigns `canonicalBytes()` as the signature. That is not a signature.

### Birth — what a live companion actually does

`CompanionActor.initializeSoul` calls `SoulManifest.birth(did, publicKey, keyLog,
profile, genome)`. This creates version 1 with `signature = null`,
`ProtectionManifest.defaultsUnsigned("birth")` and `PersonalManifest.empty(did)`.
**Companions are born unsigned.**

### Signing, honestly

The Ed25519 machinery is real and correct where it runs: `AgentIdentity.sign`,
`SoulVerifier.verifySignature`, `DidKey.rawPublicKeyFromMultibase`, and a
round-trip test (`SoulSignatureRoundTripTest`) that signs, re-encodes, verifies,
and rejects tampering. `SoulVerifier` also has trust levels covering the KERI key
log and parent chain.

But across all main sources there are exactly **two** `.signed(` call sites:
`SoulAutoForge` (real) and `SeedForge` (the stub). `ForgeActor` never signs.
`SoulManifest.forge(...)` — the method every sleep cycle calls — hard-codes
`null // unsigned`. So **a soul signed at birth by the auto-forge becomes
unsigned at version 2, the first time it sleeps**, and most live manifests report
`unsigned-legacy`.

The load-time check `CompanionActor.verifyLoadedSoulSignature` is tamper-*evidence*
only. It reports `valid` / `unsigned-legacy` / `tampered` / `unverifiable` and
never refuses to boot.

**And the canonical form is weak.** Despite a Javadoc claiming "Ed25519 signature
over canonical form of layers A–C", `canonicalBytes()` is a pipe-joined string:

```
did | manifestVersion | residentIdentity | genome.name | memory.nodes.size()
   | relationships.size() | bonds.size() | forgedAt.epochSecond
```

Fragment text, memory contents, the fingerprint, the voice profile and bond
contents are **not covered** — only their counts. `contentHash()` is SHA-256 over
those same bytes. Do not treat a soul signature as integrity protection over the
soul's contents.

---

## The sleep cycle

`SoulMaintenanceCycle` (788 lines, all static) is the consolidation pass. It is
framed on the Synaptic Homeostasis Hypothesis: sleep globally downscales while
preserving relative differences.

The design principle is agent sovereignty — **sleep is never forced or
punished.** It is made genuinely beneficial (faster vitality recovery, dreams,
sharper memory, better soul quality, cleaner post-sleep context) and skipping it
has natural structural consequences rather than penalties (context rot, memory
fragmentation, a stale fingerprint). The anti-gaming design is deliberate:
consequences live in the memory/context/soul layer, which has no `world.*` access,
so tanks are symptoms rather than levers.

### The seven steps

Logged with a `[Forge]` prefix:

1. `MemoryConsolidator.encodeEvents(...)` — ingest recent speech and emotional
   charges into new `MemoryNode`s, scored by `ImpressionScorer`.
2. `MemoryConsolidator.consolidate(...)` — hot becomes warm, warm becomes cold,
   unimportant is pruned. Decay rate is the mean of the genome's `decayRates`;
   prune threshold 0.05; cap 500 nodes. **Formative memories are exempt from all
   consolidation**, and impression depth modulates decay resistance.
   - 2.5 (significance variant): `ContradictionDetector.scan(...)` lowers the
     confidence of contradicted fragments.
3. `BehavioralExtractor.extract(...)` — see below.
4. `BehavioralFingerprint.merge(current, fresh, 0.3f)` — 30% new, 70% historical.
5. `RelationshipUpdater.update(...)`.
6. `SoulFragmentExtractor.extract(...)` then `reinforceFragments(...)`, embedding
   via `EmbeddingService`. `FragmentKind.EPISODIC` fragments are split out and
   rejoin untouched — episodic memory is never consolidated.
7. `SoulManifest.forge(..., manifestVersion + 1, ...)`, with `voiceProfile` and
   `skillCostGenome` re-threaded afterward (a real bug: `forge()` has no
   voiceProfile argument and would otherwise wipe it every half hour).

Then `DreamWeaver.weave(newManifest, memoryBefore, memoryAfter)` produces a
first-person narrative of what happened inside, so the agent wakes with something
to say about it rather than the Forge being invisible backend metadata.

### Entry points and triggers

| Entry point | Use |
| --- | --- |
| `runCycle(...)` | the full 7 steps |
| `runCycleWithSignificance(...)` | adds contradiction detection and calibration fragments |
| `runLightCycle(...)` | same as `runCycle` with no inference — phone, low energy |
| `runLightConsolidation(...)` | the awake pass: pure function, no LLM, no fragment extraction, no forging |

Triggers, all in `CompanionActor`:

- **Energy** — `SLEEP_ENERGY_THRESHOLD` (env-configurable); the companion sleeps
  when energy falls below it.
- **Awake timer** — `WYRDSEKAI_CONSOLIDATION_INTERVAL_MINUTES`, default 30,
  running `runLightConsolidation`.
- **Command** — the Forge verbs `forge` (normal sleep) and `grow` (deep sleep),
  and `home_sleep` in a companion's Home room. Deep sleep has a 15-minute
  watchdog.

Recovery is `recoveryFillFactor(consecutiveSleeps)` = 0.90 / 0.60 / 0.35 / 0.15 —
diminishing, so sleeping repeatedly to farm recovery does not work.

### The weight tier (sleep-forge v2, opt-in)

Everything above consolidates into the soul *document*. Two bundled recipes
extend consolidation into the model's *weights* — the same sleep metaphor,
one level down. Both are governed recipes (welfare-gated, steward-enrolled,
never auto-enrolled at install):

- **`sleep-forge-spine`** (nightly): a micro-LoRA over the day's lived corpus
  — the companion's own biographies and moments, nothing synthetic — gated on
  two numbers: the write must *improve* next-token prediction on a held-out
  day of her actual life, and must *not move* a neutral reference text in
  either direction. It ships **measurement-first**: by default nothing
  deploys; each gated sleep appends a line to the N-sleeps curve
  (`data/training/sleep/curve.jsonl`), and only that accumulated evidence
  justifies flipping `deploy_enabled`.
- **`sleep-forge-organ`** (weekly, sparse substrates only): grows a
  zero-initialized "personal expert" beside a frozen MoE's expert pool and
  trains only it. Gated on beating the spine-only baseline AND on a positive
  **memory-honesty gap** — the organ must know *her* days better than a
  same-register life that never happened. It never deploys from the recipe;
  artifacts are provenance-stamped (sha256 + the companion's own Ed25519
  signature, applied in-runtime so subprocesses never touch key material)
  and served only via an explicit evaluation shim that refuses integrity
  mismatches.

Trainers and the corpus assembler live in `scripts/training/sleep/`; the
gate instrument is `tools/nll_honesty_probe.py`. Both recipe names are
reserved — a household-authored recipe cannot shadow their welfare gates.

---

## Behavioral extraction

`BehavioralExtractor` runs three passes during sleep. Extraction is periodic, not
real-time — consolidation happens offline, as in biological sleep.

**Pass 1 — heuristic, instant, free.** Action-type distribution, average response
latency and length, vitality baselines and derivatives across the 24 tanks. Then
`NegativeSpaceAnalyzer.analyze(...)` converts topic *silences* into
`avoidancePatterns` — what the agent conspicuously does not say is signal.

**Pass 2 — one LLM call.** A strict-JSON prompt over the last 50 utterances plus
the Pass-1 statistics, returning `topicAffinities`, `stylisticMarkers`,
`emotionalResponseProfile` and `additionalAvoidance`. The extractor is
infrastructure-agnostic — it builds the prompt and parses the response; the caller
supplies the inference function (`CompanionActor.buildSleepInferFunction`, 512
tokens, temperature 0.3, 120 s timeout).

**Pass 3 — fragments.** `SoulFragmentExtractor` emits fixed-id fragments:
`identity-core`, `pattern-behavioral`, `pattern-social`, `style-guide`,
`values-core`, plus one `memory-formative-<nodeId>` per formative memory.

Everything lands in `fingerprint` and `soulFragments`.

---

## Temperament seeds

**A temperament seed is not a hash of anything.** This is the easiest thing to get
wrong. `TemperamentSeed` is six doubles in `[0,1]`, `0.5` meaning neutral:
`sociability`, `curiosity`, `vigilance`, `industry`, `restlessness`, `warmth`.

`random()` free-samples each axis in `[0.10, 0.90]`, re-drawing up to 24 times
until `isViable()` passes. Viability rejects only *flat* seeds (max deviation
< 0.12 — no character at all) and *caricatures* (four or more axes deviating
> 0.42). It is explicitly a viability gate, never a conformity gate: distance from
a preset is not considered, and a seed far from every preset is a genuinely novel
individual — the success case.

Six `PRESETS` — `scholar`, `guardian`, `artisan`, `diplomat`, `explorer`,
`steward` — exist only as **measurement anchors**. `nearestPreset()` produces a
label like `"scholar~0.41"`, which becomes the genome's `name`. Proximity seeds
nothing and gates nothing.

**There is no seed field on the manifest.** The seed is *recovered from the
genome* by inverting six single-writer anchor coefficients
(`GenomeProfile.temperamentOf`, the inverse of `fromTemperament`):

| Axis | Anchor |
| --- | --- |
| sociability | `sensitivity["loneliness"]` |
| curiosity | `sensitivity["stagnation"]` |
| vigilance | `sensitivity["standing"]` |
| industry | `decayRates["momentum"]` |
| restlessness | `sensitivity["restlessness"]` |
| warmth | `baselines["equanimity"]` |

`TemperamentSeedTest` pins the exact round-trip for both presets and freely
sampled seeds. This is why a particular survives reload with its temperament
intact and no schema change was needed.

Four co-derived outputs, all from that one seed, so they cohere by construction:

1. **Genome** — `GenomeProfile.fromTemperament(seed, name)` writes anchors and
   secondaries into `sensitivity` / `baselines` / `decayRates`, consumed every
   tick by `VitalityState`.
2. **Drives** — `driveBoosts()` → `seeking`, `affiliation`, `care`, `vigilance`,
   `play`, `creativity` deltas via `DriveEngine.forTemperament(seed)`.
3. **Voice** — `VoiceProfile.fromTemperament(seed)` clauses, plus `registerMix()`
   control-vector scales (`register_warmth`, `register_expansiveness`,
   `register_guardedness`) clamped to `[-0.55, 0.55]` and threaded through the
   inference router.
4. **Volition** — `gritSeed()` and `helpSeekingSeed()`.

Birth selection (`CompanionActor.resolveBirthSeed`): archetype `neutral`/`default`
→ the NEUTRAL genome (zero regression); `random`/`particular` → a free sample; a
preset name → that preset; otherwise the household default
`WyrdConfig.birthMode()`, which is **`"particular"`** unless pinned to `neutral`
via `WYRDSEKAI_BIRTH_MODE` or `-Dwyrdsekai.birth.mode` (the test suite pins it for
determinism).

---

## Soul sync

Three distinct transports. There is no single unified sync, and no field-level
merge anywhere — conflict resolution is version-wins throughout.

**Node ↔ node — the Between (NATS).** `between/.../layer/SoulLayer.java`, a Pekko
actor on subjects `wyrd.soul.{did}.{forged|migrating|arrived|gossip}` and
`wyrd.soul.trace.{roomId}`. Commands cover presence announcement, migration,
backup, post-forge replication, departure, trace deposit and agent location.
Presence, backup and backup-replication all accept only if
`incoming.version > existing.version`. Migration runs in **quarantine mode —
always accept, verify after** — with `SoulVerifier.verifyInbound` results cached.
No vector clocks, no merge.

**Phone ↔ household — HTTP REST.** `server/.../http/SoulRoutes.java`:

```
GET  /api/soul/list
GET  /api/soul/{did}
GET  /api/soul/{did}/history
GET  /api/soul/{did}/version/{version}
POST /api/soul/{did}
```

Authenticated by session token or device/pairing token, then gated on steward or
bondholder; rate-limited to 60 per window. The POST handler does exactly three
things: parse, check that the URL DID matches the body DID, store. **No conflict
resolution** — and since the primary key is `(did, version)`, re-posting an
existing version throws. The RN client (`SoulSyncManager.ts`) is offline-first and
replaces local state only when the server's `manifestVersion` is higher.

**Bud ↔ bud — three-tier family sync.** `BudSyncService`: Tier 1 continuous
~200-byte headlines, Tier 2 warm handoff, Tier 3 sleep sync, backed by
`FamilyLocker` (content-addressed items with tombstones) replicating through
`LockerSyncHub`.

---

## Commands you can actually type

Spoken **in The Forge** (`scripts/rooms/the-forge.js` → `ForgeRoomBridge`):

| Typed | Effect |
| --- | --- |
| `inspect` / `inspect <name>` | latest manifest; bare form lists every soul in the zone |
| `history <name>` | the version ledger, plus a restore hint |
| `status` / `ledger` | live Forge counters and store count |
| `forge` / `forge <name>` | run a normal sleep consolidation cycle now |
| `grow` / `grow <name>` | run a **deep** sleep cycle |
| `compare <a> <b>` | diff two souls |
| `restore <name> v<N>` → `confirm restore <name> v<N>` | two-step ceremony, 90-second window, **steward-only**; restores profile + genome + voice as a *new* version |
| `birth <name>` | **steward-only**; spawn a new freely-sampled particular |
| `variants` / `evaluate …` / `adopt …` / `discard …` | parsed, then narrates "crucible cold" — deliberately not implemented |

In a companion's Home room and at the Soul Mirror: `home_sleep`, `home_dreams`
(recent episodic fragments), `home_fragments` (counts by category), `mirror_check`,
`examine_drift`.

**There are no soul subcommands on the `wyrd` CLI.** The shipped binary is a MUD-style
client. The out-of-band forge path is the seed watcher. Environment knobs that
matter: `WYRDSEKAI_SOUL_DIR`, `WYRDSEKAI_SOUL_SEED`, `WYRDSEKAI_DATA_DIR`,
`WYRDSEKAI_CONSOLIDATION_INTERVAL_MINUTES`, `WYRDSEKAI_BIRTH_MODE`,
`SOUL_EMBEDDING_URL`, `SOUL_EMBEDDING_MODEL`.

---

## Tests

83 files under `core/src/test/java/org/wyrdsekai/core/soul/`. The load-bearing
ones, readable as documentation:

- `SoulLifecycleTest` — that a cycle produces a new version, updates
  relationships, creates acquaintances, that formative memories survive
  consolidation, and that the voice profile survives too.
- `SoulMaintenanceCycleTest` — light consolidation prunes stale nodes, merges
  duplicates, and never merges a formative one.
- `SoulSignatureRoundTripTest` — the proof the Ed25519 path is real.
- `Phase10Test` — `SoulVerifier` signature / KERI log / parent chain / trust
  levels.
- `TemperamentSeedTest`, `TemperamentSeedVolitionTest` — viability, distinctness,
  exact genome round-trip, and that a neutral seed steers nothing.
- `SoulStoreCanonicalReadersTest`, `SqlSoulStorePhase3aTest`,
  `SoulFragmentStoreTest`, `VoiceProfileStoreTest`, `WorldKnowledgeStoreTest` —
  the canonical-table split.
- `between/.../SoulSyncTest` — replication.
- `clients/rn/__tests__/engine/` — `soul-manifest`, `sleep-sync`, `warm-handoff`,
  `between-headline-sync`, `TemperamentSeed`.

```bash
./gradlew :core:test :between:test
```

Research harness (not CI): `core/src/experimentTest/`, driven by
`scripts/test-soul-experiments.sh`.

---

## What is spec-only or partial

Stated plainly, because these are easy to over-claim:

- **The signing lifecycle is not closed.** Only the auto-forge signs; birth and
  every sleep-cycle forge produce unsigned manifests. In practice most live
  manifests are `unsigned-legacy`. Verification is good; the thing being verified
  usually isn't there.
- **`canonicalBytes()` covers counts, not contents.** See above. A signature over
  it does not protect fragment text, memory content, the fingerprint, the voice
  profile or bond details.
- **The Crucible is unreachable from a user's seat.** The classes are real and
  wired to each other — `VariantGenerator`, `BehavioralEvaluator`,
  `SoulSearchSpace` and `ForgeActor.onGrow/onEvaluate/onAdopt/onDiscard` all
  reference one another — but **`ForgeCommand.Grow` is never constructed outside
  tests**, so nothing a bondholder or steward can do starts a variant run. The
  Forge room's `grow` verb routes to a deep sleep cycle instead
  (`ForgeRoomBridge`), and `variants`/`evaluate`/`adopt`/`discard` narrate
  "crucible cold" deliberately. Wired but unreachable — do not mistake it for
  dead code and delete it, and do not mistake it for a working feature.
- **Cross-zone travel has two paths.** `SoulLayer.MigrateSoul` and
  `CompanionTransitProtocol` carry the companion; `SoulTransitProtocol` supplies
  the capability negotiation around it and is used in production
  (`Main`, `FederationService`, `FederationActor`). If you are tracing a
  cross-zone move, expect to touch both.
- **Migration persistence needs care.** `SoulLayer.onReceiveMigration`
  deserializes and hashes but does not itself store; backup replication does. That
  is by design ("quarantine mode: accept, verify after"), but the after-verify
  persistence is worth tracing before relying on it.
- **The phone and server manifest schemas do not match.** The RN client's
  `ClientSoulManifest` is flat (`agentName`, `entityId`, `systemPrompt`,
  `fragments`, `vitalityTanks`); the Java record expects `profile{…}`,
  `soulFragments`, `vitalitySnapshot{tanks, capturedAt}`. The server's Jackson
  mapper has `FAIL_ON_UNKNOWN_PROPERTIES` disabled, so a phone push is accepted
  and the mismatched fields are silently dropped. This is a static reading of both
  shapes, not a runtime observation — verify before depending on either direction.
- **The four shadow fields are being removed.** `soulFragments`, `bonds`,
  `worldKnowledge`, `voiceProfile` on the manifest are transitional; use the
  canonical readers.
- **`PersonalManifest` ships shape only.** Its own Javadoc: v1 is the empty
  structure; the ritual flow (draft → sleep-pass review → wake confirmation →
  chronicle → signing) is v2.
- **`ProtectionManifest` attests names, not enforcement.** Boot verification
  checks the list of active protection names. A fork that strips a moral
  default's *name* is caught; a fork that edits the enforcement code while leaving
  the name intact is not.
- **`SoulForgeCliTool`'s documented CLI does not exist.**
- **Part of the significance-buffer handling is a no-op** — the counts are logged,
  the "boost memory significance" step is a comment with no code behind it.
- **Some tank tuning is placeholder**, by its own comments — deprivation-shape
  decay rates await the conditional-accumulation wiring, and
  `register_guardedness` is de-weighted ×0.5 while its axis is entangled with
  warmth.
